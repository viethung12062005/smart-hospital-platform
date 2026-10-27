# 06 — Dữ liệu AI (ai service)

Kho: MongoDB database `ai` (cùng cluster với clinical, user riêng) + Azure AI Search (local: OpenSearch) + Blob container `kb`.

## 1. Kho tri thức RAG

### 1.1 Vòng đời tài liệu tri thức (`kb_documents`)

`draft` (admin tải lên) → `approved` (trưởng khoa/admin duyệt, UC-11 "tài liệu đã duyệt") → `indexed` (đã chunk + embed) →
`retired` (gỡ khỏi index, giữ bản ghi). Chỉ tài liệu `indexed` được truy xuất.

| Trường | Kiểu | Ghi chú |
|---|---|---|
| `_id` | uuid | |
| `title`, `source` | string | nguồn: "Phác đồ nội bộ khoa Tim mạch 2026" (tài liệu giả định/công khai) |
| `department` | string | chuyên khoa (value set `specialty`) hoặc `hospital-wide` |
| `allowedRoles[]` | string[] | `doctor`, `nurse` — dùng cho security trimming |
| `version` | int | tăng khi thay file; version cũ bị gỡ khỏi index |
| `status` | enum | `draft` · `approved` · `indexed` · `retired` |
| `blobPath`, `contentType`, `sha256` | | file gốc trên container `kb` |
| `chunkCount`, `indexedAt`, `embeddingModel` | | |
| `approvedBy`, `approvedAt`, `createdBy`, `createdAt` | | |

Index: `{status:1, department:1}`.

### 1.2 Index AI Search `kb-chunks-v1`

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

| Tham số | Giá trị | Lý do |
|---|---|---|
| Chunk | 600 token, overlap 80, tách theo tiêu đề mục trước rồi mới theo độ dài | Giữ nguyên ngữ cảnh mục phác đồ |
| Embedding | `bge-m3` (1024 chiều) chạy local qua Ollama ([ADR-0013](../adr/0013-llm-deepseek-gemini-local-embedding.md)) | Miễn phí, đa ngôn ngữ tốt cho tiếng Việt; đổi model = tạo index `v2` và re-index (không trộn vector khác model) |
| Truy xuất | Hybrid: BM25 + vector (top 50) → RRF → (tùy chọn) rerank cross-encoder local → top 5 | Đúng pipeline Retrieve → Rerank của đề |
| Security trimming | `$filter = allowedRoles/any(r: r eq '<role>') and (department eq '<khoa>' or department eq 'hospital-wide')` | Lọc ngay trong truy vấn (UC-11), không lọc sau |
| Fallback | Embedding lỗi → chỉ BM25 + reranker; rerank lỗi → hybrid không rerank | Xuống cấp có kiểm soát (ADR-0006) |

## 2. Hội thoại, câu trả lời và phản hồi

| Collection | Trường chính | Index / TTL |
|---|---|---|
| `conversations` | `_id`, `userSubject`, `kind` (`assistant` · `booking-bot`), `patientContext` (Patient id khi bot đặt lịch cho chính bệnh nhân), `createdAt`, `lastMessageAt` | `{userSubject:1, lastMessageAt:-1}` · TTL 30 ngày trên `lastMessageAt` |
| `messages` | `_id`, `conversationId`, `role` (`user` · `assistant` · `tool`), `content` (đã redact PII nếu đi ra provider ngoài Azure), `citations[]` (`{chunkId, documentId, title, section, page}`), `toolCalls[]`, `model`, `provider`, `fallbackLevel` (0 = model chính), `tokens` (`in`, `out`), `costUsd`, `latencyMs`, `guardrail` (`passed` · `blocked:<lý do>`), `createdAt` | `{conversationId:1, createdAt:1}` · TTL 30 ngày |
| `feedback` | `_id`, `messageId`, `userSubject`, `rating` (`up` · `down`), `comment`, `createdAt` | `{messageId:1}` (giữ lâu dài để làm eval) |
| `pending_actions` | `_id`, `conversationId`, `tool` (`book_appointment` · `cancel_appointment`), `arguments`, `expiresAt`, `confirmedAt` | TTL trên `expiresAt` (5 phút) — hành động chatbot chỉ chạy khi người dùng xác nhận (AC-14.1) |

## 3. Trích xuất tài liệu

`extraction_jobs`:

| Trường | Ghi chú |
|---|---|
| `_id`, `documentId`, `documentVersion`, `patientId` | `{documentId, documentVersion}` **unique** — event lặp không tạo job trùng |
| `status` | state machine trong [01-domain-model.md](01-domain-model.md) §5 |
| `attempts`, `lastError`, `nextAttemptAt` | retry tối đa 3, backoff 1/5/15 phút |
| `ocr` | `{engine, pages, textBlobPath}` (văn bản OCR lưu Blob, không lưu trong document) |
| `classification` | `{docType (LOINC), confidence}` |
| `fields[]` | `{name, value, unit, confidence, page, bbox}`, ví dụ `{name: "glucose", value: 6.8, unit: "mmol/L", confidence: 0.93}` |
| `overallConfidence` | min của các trường bắt buộc |
| `review` | `{reviewedBy, reviewedAt, decision (accepted · rejected), editedFields[], createdObservationIds[]}` |
| `model`, `provider`, `fallbackLevel`, `tokens`, `costUsd` | |

Index: `{status:1, nextAttemptAt:1}` (worker lấy việc), `{patientId:1, createdAt:-1}` (hàng chờ duyệt theo bệnh nhân).

Ngưỡng: `overallConfidence ≥ 0.85` → đánh dấu "đề xuất chấp nhận"; thấp hơn → "cần kiểm tra kỹ". MVP **luôn cần người
duyệt** (AC-13.1). Khi duyệt, ai service gọi clinical tạo `Observation` (`derivedFrom = DocumentReference`) **bằng token
của người duyệt**.

## 4. Cấu hình chuỗi model (router)

Cấu hình nằm trong ConfigMap (Helm values), không nằm trong DB:

```yaml
tasks:
  rag-answer:
    timeoutSeconds: 12
    maxOutputTokens: 800
    chain:
      - { provider: deepseek, model: deepseek-chat, redactPii: true }
      - { provider: gemini, model: gemini-flash, redactPii: true }     # free tier, hạn mức thấp
      - { provider: ollama, model: qwen2.5-7b-instruct, redactPii: false }   # chạy trong cluster, node pool ai
    finalFallback: retrieval-only          # trả danh sách tài liệu liên quan
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
Tên model là giá trị cấu hình, chốt khi dựng môi trường theo danh mục model sẵn có của DeepSeek/Gemini lúc đó; API key lấy từ Key Vault (không nằm trong ConfigMap). Chuỗi do [ADR-0013](../adr/0013-llm-deepseek-gemini-local-embedding.md) quy định.
mọi model trong chuỗi đều phải chạy qua bộ eval (NFR-AI7) trước khi đưa vào chuỗi.

Prompt template được version hóa trong repo (`prompts/<task>/v<N>.md`), ID phiên bản prompt được ghi vào `messages` để truy vết.
