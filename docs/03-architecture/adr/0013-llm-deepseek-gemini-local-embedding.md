# ADR-0013: DeepSeek as primary model, Gemini free tier as fallback, local embeddings

## Status
Accepted (2026-10-27) — SA/PM decision. Replaces the model chain part of [ADR-0006](0006-llm-router-fallback.md); the router mechanism, circuit breaker, and token budget remain unchanged.

## Context
Cost is the main constraint of the capstone project; do not use paid cloud-hosted models (Azure OpenAI, Claude, Azure Document Intelligence).
The data is synthetic, but the design must still be correct for a real system (NFR-SEC8).

## Decision
| Task | Primary | Fallback 1 | Fallback 2 | Last resort |
|---|---|---|---|---|
| Knowledge assistant RAG | **DeepSeek** (API, via OpenAI-compatible) | **Gemini free tier** | Ollama `qwen2.5` in the cluster | Return only a list of relevant documents |
| Record summarization | DeepSeek | Gemini | — | Structured timeline (no LLM) |
| Document extraction | **Tesseract (local OCR)** → DeepSeek extracts fields | Gemini | — | Human review queue |
| Booking chatbot | DeepSeek (tool calling) | Gemini (tool calling) | — | Regular booking form |
| Embedding | **bge-m3 running locally** (Ollama, 1024 dimensions) | — | — | BM25 only |

- **Every call to DeepSeek and Gemini must go through PII redaction** (no more `redactPii: false` flag except for Ollama).
- The Gemini free tier has low quotas and data may be used to improve the product: fallback only, receives only redacted text, and synthetic data only.
- Embeddings and Ollama run in the cluster (`ai` node pool, CPU). Changing the embedding model = index `v2` (do not mix vectors).
- Retrieval: hybrid BM25 + vector (RRF) in AI Search; **do not use Azure's semantic ranker**. The rerank step with a local cross-encoder is optional, enabled only if eval (NFR-AI1) shows it is needed.
- DeepSeek/Gemini keys are secrets: Key Vault + CSI in the cloud; locally in `dev/.local/` (not committed), or left empty to run with Ollama.
- Eval runs on **each** model in the chain (NFR-AI7), including when DeepSeek/Gemini change versions.

## Alternatives considered
- DeepSeek on Azure AI Foundry: data stays in Azure but costs more and is no longer "free"; rejected per the cost requirement.
- Gemini as the primary model: free-tier call limits are too low for RAG load; only suitable as fallback.

## Consequences
- External API stability/latency risk → keep the fallback chain and Ollama at the end; NFR-P5/P6 measure the fallback path too.
- Need an `ai` node pool with enough RAM for Ollama (bge-m3 + qwen2.5-7b ≈ 8–10 GB); UAT can use smaller models.
- Remove the Azure OpenAI/Document Intelligence modules from IaC (IAC-19 content changes).
- During the defense, state: the real system only sends redacted text, or uses self-hosted models.
