# 06 — AI data (ai service)

Store: MongoDB database `ai` (same cluster as clinical, separate user) + Azure AI Search (local: OpenSearch) + Blob container `kb`.

## 1. RAG knowledge base

### 1.1 Knowledge document lifecycle (`kb_documents`)

`draft` (admin uploads) → `approved` (head of department/admin approves, UC-11 "approved documents") → `indexed` (chunked + embedded) →
`retired` (removed from the index, record kept). Only `indexed` documents are retrievable.

| Field | Type | Note |
|---|---|---|
| `_id` | uuid | |
| `title`, `source` | string | source: "Internal protocol of the Cardiology department 2026" (fictional/public document) |
| `department` | string | specialty (value set `specialty`) or `hospital-wide` |
| `allowedRoles[]` | string[] | `doctor`, `nurse` — used for security trimming |
| `version` | int | incremented when the file is replaced; the old version is removed from the index |
| `status` | enum | `draft` · `approved` · `indexed` · `retired` |
| `blobPath`, `contentType`, `sha256` | | original file on the `kb` container |
| `chunkCount`, `indexedAt`, `embeddingModel` | | |
| `approvedBy`, `approvedAt`, `createdBy`, `createdAt` | | |

Index: `{status:1, department:1}`.

### 1.2 AI Search index `kb-chunks-v1`

```json
{
  "name": "kb-chunks-v1",
  "fields": [
    { "name": "id", "type": "Edm.String", "key": true },
    { "name": "documentId", "type": "Edm.String", "filterable": true },
    { "name": "documentVersion", "type": "Edm.Int32", "filterable": true },
    { "name": "title", "type": "Edm.String", "searchable": true, "analyzer": "vi.microsoft" },
    { "name": "section", "type": "Edm.String", "searchable": true, "analyzer": "vi.microsoft" },
    { "name": "page", "type": "Edm.Int32" },
    { "name": "department", "type": "Edm.String", "filterable": true, "facetable": true },
    { "name": "allowedRoles", "type": "Collection(Edm.String)", "filterable": true },
    { "name": "content", "type": "Edm.String", "searchable": true, "analyzer": "vi.microsoft" },
    { "name": "contentVector", "type": "Collection(Edm.Single)", "searchable": true,
      "dimensions": 1024, "vectorSearchProfile": "hnsw-cosine" },
    { "name": "updatedAt", "type": "Edm.DateTimeOffset", "filterable": true }
  ],
  "vectorSearch": {
    "algorithms": [ { "name": "hnsw", "kind": "hnsw", "hnswParameters": { "metric": "cosine", "m": 16, "efConstruction": 400 } } ],
    "profiles": [ { "name": "hnsw-cosine", "algorithm": "hnsw" } ]
  }
}
```

| Parameter | Value | Reason |
|---|---|---|
| Chunk | 600 tokens, overlap 80, split by section heading first and then by length | Preserves the section context of the protocol |
| Embedding | `bge-m3` (1024 dimensions) run locally via Ollama ([ADR-0013](../adr/0013-llm-deepseek-gemini-local-embedding.md)) | Free, good multilingual support for Vietnamese; changing the model = create a `v2` index and re-index (never mix vectors from different models) |
| Retrieval | Hybrid: BM25 + vector (top 50) → RRF → (optional) local cross-encoder rerank → top 5 | Matches the project's Retrieve → Rerank pipeline |
| Security trimming | `$filter = allowedRoles/any(r: r eq '<role>') and (department eq '<specialty>' or department eq 'hospital-wide')` | Filter inside the query (UC-11), not afterwards |
| Fallback | Embedding fails → BM25 + reranker only; rerank fails → hybrid without rerank | Controlled degradation (ADR-0006) |

## 2. Conversations, answers, and feedback

| Collection | Main fields | Index / TTL |
|---|---|---|
| `conversations` | `_id`, `userSubject`, `kind` (`assistant` · `booking-bot`), `patientContext` (Patient id when the bot books for the patient themselves), `createdAt`, `lastMessageAt` | `{userSubject:1, lastMessageAt:-1}` · TTL 30 days on `lastMessageAt` |
| `messages` | `_id`, `conversationId`, `role` (`user` · `assistant` · `tool`), `content` (PII redacted if sent to a provider outside Azure), `citations[]` (`{chunkId, documentId, title, section, page}`), `toolCalls[]`, `model`, `provider`, `fallbackLevel` (0 = primary model), `tokens` (`in`, `out`), `costUsd`, `latencyMs`, `guardrail` (`passed` · `blocked:<reason>`), `createdAt` | `{conversationId:1, createdAt:1}` · TTL 30 days |
| `feedback` | `_id`, `messageId`, `userSubject`, `rating` (`up` · `down`), `comment`, `createdAt` | `{messageId:1}` (kept long-term for eval) |
| `pending_actions` | `_id`, `conversationId`, `tool` (`book_appointment` · `cancel_appointment`), `arguments`, `expiresAt`, `confirmedAt` | TTL on `expiresAt` (5 minutes) — chatbot actions run only when the user confirms (AC-14.1) |

## 3. Document extraction

`extraction_jobs`:

| Field | Note |
|---|---|
| `_id`, `documentId`, `documentVersion`, `patientId` | `{documentId, documentVersion}` **unique** — duplicate events do not create duplicate jobs |
| `status` | state machine in [01-domain-model.md](01-domain-model.md) §5 |
| `attempts`, `lastError`, `nextAttemptAt` | retry at most 3 times, backoff 1/5/15 minutes |
| `ocr` | `{engine, pages, textBlobPath}` (OCR text stored in Blob, not in the document) |
| `classification` | `{docType (LOINC), confidence}` |
| `fields[]` | `{name, value, unit, confidence, page, bbox}`, for example `{name: "glucose", value: 6.8, unit: "mmol/L", confidence: 0.93}` |
| `overallConfidence` | minimum of the required fields |
| `review` | `{reviewedBy, reviewedAt, decision (accepted · rejected), editedFields[], createdObservationIds[]}` |
| `model`, `provider`, `fallbackLevel`, `tokens`, `costUsd` | |

Index: `{status:1, nextAttemptAt:1}` (worker fetches work), `{patientId:1, createdAt:-1}` (review queue by patient).

Threshold: `overallConfidence ≥ 0.85` → mark "suggest accept"; lower → "needs careful review". MVP **always requires a human
reviewer** (AC-13.1). On approval, the ai service calls clinical to create an `Observation` (`derivedFrom = DocumentReference`) **using the
reviewer's token**.

## 4. Model chain configuration (router)

Configuration is in a ConfigMap (Helm values), not in the DB:

```yaml
tasks:
  rag-answer:
    timeoutSeconds: 12
    maxOutputTokens: 800
    chain:
      - { provider: deepseek, model: deepseek-chat, redactPii: true }
      - { provider: gemini, model: gemini-flash, redactPii: true }     # free tier, low quota
      - { provider: ollama, model: qwen2.5-7b-instruct, redactPii: false }   # runs in the cluster, ai node pool
    finalFallback: retrieval-only          # return the list of related documents
  patient-summary:
    timeoutSeconds: 15
    chain: [ { provider: deepseek, model: deepseek-chat, redactPii: true }, { provider: gemini, model: gemini-flash, redactPii: true } ]
    finalFallback: structured-timeline
  extraction:
    chain: [ { provider: tesseract-ocr }, { provider: deepseek, model: deepseek-chat, redactPii: true }, { provider: gemini, model: gemini-flash, redactPii: true } ]
    finalFallback: human-review
  booking-agent:
    timeoutSeconds: 10
    chain: [ { provider: deepseek, model: deepseek-chat, tools: true, redactPii: true }, { provider: gemini, model: gemini-flash, tools: true, redactPii: true } ]
    finalFallback: booking-form
embedding: { provider: ollama, model: bge-m3, dimensions: 1024 }
circuitBreaker: { failureThreshold: 5, windowSeconds: 60, openSeconds: 30 }
budget: { dailyTokenLimit: 2000000, alertAtPercent: 80 }
```
Model names are configuration values, fixed when the environment is built according to the DeepSeek/Gemini model catalog available at that
time; API keys come from Key Vault (not in the ConfigMap). The chain is specified by [ADR-0013](../adr/0013-llm-deepseek-gemini-local-embedding.md).
Every model in the chain must pass the eval suite (NFR-AI7) before being added to the chain.

Prompt templates are versioned in the repo (`prompts/<task>/v<N>.md`); the prompt version ID is recorded in `messages` for traceability.

## 5. Messaging and idempotency

Database `ai` has three additional platform collections. `outbox` and `inbox` have the same definition as [03](03-db-clinical-document.md) §2.7;
`idempotency` (named differently from Java's `idempotency_keys` because the schema differs) is the **stateful variant** (§5.1), because
ai's operations call other services.

| Collection | Used for | Note |
|---|---|---|
| `outbox` | Emits E11 `audit.recorded` for `ai-query`, `ai-summary` ([05](05-storage-audit-blob.md) §1.1) | Written in the same transaction as `messages` / `extraction_jobs`; relay same as Java (`shp_platform.mongo`). Reads of records on behalf of the user are audited by clinical itself |
| `inbox` | Receives E8 `document-uploaded` (consumer `ai`) | `insert inbox` + create `extraction_jobs` in **one transaction**: a duplicate event skips the whole event. The unique index `{documentId, documentVersion}` on `extraction_jobs` is the second layer |
| `idempotency` | AI4 summary, AI6 extraction review, AI8 chatbot action confirmation ([12](12-api-ai.md)) | A repeated request returns exactly the old response: AI6 does not create the `Observation` twice, AI8 does not book two appointments |

### 5.1 ai `idempotency`

Unlike Java (acquires the lock in the same transaction as the data, [03](03-db-clinical-document.md) §2.7): AI4, AI6, AI8 call clinical/scheduling/LLM over the
network and cannot be wrapped in one MongoDB transaction. Therefore the key is stateful and stores the response.

| Field | Meaning |
|---|---|
| `_id` | `SHA-256(<subject>\|<scope>\|<key>)`; `scope` = operation name (`summary`, `extraction-review`, `booking-confirm`) |
| `hash` | SHA-256 of the body (JSON with sorted keys) — the body is not stored |
| `state` | `processing` → `done` |
| `response` | `{status, body}` from the first processing (only present when `done`) |
| `createdAt` | **TTL** 24 hours |

| Situation | Result |
|---|---|
| New key | Write `processing`, process; on success → `done` + store the response; on error → **delete the record** so the user can retry with the same key |
| Same key, same body, `done` | Return the stored response |
| Same key, same body, `processing` | **409** `invalid-state-transition` |
| Same key, different body | **422** `idempotency-key-reused` |

Security (health data):
- `response` of AI4 is a record summary (PHI). The collection is protected like `messages`: only the ai DB user can read/write, encrypted at rest, TTL 24 hours, **do not log** the content.
- **Check permissions before returning a replay**: validate the token, that the account is not locked, and that the care relationship is still valid — exactly like a new request. A person whose permission was revoked within 24 hours cannot retrieve the stored copy.
- AI8 calls `$book`/`$cancel` with an `Idempotency-Key` (required by scheduling), so scheduling blocks duplicates with one more layer.

Implementation: `libs/python/shp_platform/idempotency.py` (`COLLECTION = "idempotency"`, TTL index of 24 hours created by `ensure_indexes`), used in `api_summary.py` (AI4) and `api_booking.py` (AI8).
The Python library does not yet have a stateless version like Java's `MongoIdempotency`; any Python service that needs one uses this stateful version.
