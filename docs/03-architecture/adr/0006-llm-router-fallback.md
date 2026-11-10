# ADR-0006: Multi-model LLM router with fallback

## Status
Accepted (2026-10-26). **The model chain table below is replaced by [ADR-0013](0013-llm-deepseek-gemini-local-embedding.md)** (2026-10-27); the rest remains in effect.

## Decision
`ai-service` has a small hand-written router. Each task declares an **ordered model chain**:

| Task | Primary | Fallback 1 | Fallback 2 | Last resort |
|---|---|---|---|---|
| Knowledge assistant RAG | Azure OpenAI (GPT-4.x) | Claude (Azure AI Foundry / Anthropic API) | Local open model (Ollama) | Return a list of relevant documents, do not generate an answer |
| Record summarization | Azure OpenAI | Claude | — | Structured timeline (no LLM) |
| Document extraction | Azure Document Intelligence + LLM | Claude | Tesseract + regex | Place in the human review queue |
| Booking chatbot | Model supporting tool calling | Another model supporting tool calling | — | Switch to the regular booking form |

Fallback is triggered when: timeout, 5xx/429 error, circuit breaker open, token budget exceeded,
or output fails schema validation. Every fallback is recorded as a metric and trace.

Data is sent to providers outside Azure **only after PII redaction**.

## Alternatives considered
- **LiteLLM proxy**: feature-rich but one more system to operate and hard to explain
  fallback behavior; a hand-written router is ~200 lines and testable.
- **A single model**: simple but does not meet the high-availability requirement.

## Consequences
Need an evaluation (eval) suite run on each model in the chain to know quality when falling back.
