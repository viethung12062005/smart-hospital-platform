# ADR-0006: LLM router đa model với fallback

## Trạng thái
Accepted (2026-10-26) — đã được Solution Architect/PM duyệt cùng cổng G3.

## Quyết định
`ai-service` có một router nhỏ tự viết. Mỗi tác vụ khai báo **chuỗi model theo thứ tự**:

| Tác vụ | Chính | Fallback 1 | Fallback 2 | Cuối cùng |
|---|---|---|---|---|
| RAG trợ lý tri thức | Azure OpenAI (GPT-4.x) | Claude (Azure AI Foundry / Anthropic API) | Model mở local (Ollama) | Trả về danh sách tài liệu liên quan, không sinh câu trả lời |
| Tóm tắt hồ sơ | Azure OpenAI | Claude | — | Timeline có cấu trúc (không LLM) |
| Trích xuất tài liệu | Azure Document Intelligence + LLM | Claude | Tesseract + regex | Đưa vào hàng đợi human review |
| Chatbot đặt lịch | Model hỗ trợ tool calling | Model khác hỗ trợ tool calling | — | Chuyển sang form đặt lịch thường |

Chuyển sang fallback khi: timeout, lỗi 5xx/429, circuit breaker mở, vượt ngân sách token,
hoặc output không qua kiểm tra schema. Mọi lần fallback được ghi metric và trace.

Dữ liệu chỉ gửi tới provider ngoài Azure **sau khi redact PII**.

## Phương án đã cân nhắc
- **LiteLLM proxy**: nhiều tính năng nhưng là thêm một hệ thống phải vận hành và khó giải thích
  hành vi fallback; router tự viết ~200 dòng, kiểm thử được.
- **Một model duy nhất**: đơn giản nhưng không đáp ứng yêu cầu sẵn sàng cao.

## Hệ quả
Cần bộ đánh giá (eval) chạy trên từng model trong chuỗi để biết chất lượng khi fallback.
