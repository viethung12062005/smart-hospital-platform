# ADR-0013: DeepSeek làm model chính, Gemini free tier dự phòng, embedding chạy local

## Trạng thái
Accepted (2026-10-27) — SA/PM quyết định. Thay phần chuỗi model của [ADR-0006](0006-llm-router-fallback.md); cơ chế router, circuit breaker, ngân sách token giữ nguyên.

## Bối cảnh
Chi phí là ràng buộc chính của đồ án; không dùng model chạy trên cloud trả phí (Azure OpenAI, Claude, Azure Document Intelligence).
Dữ liệu chỉ là dữ liệu giả, nhưng thiết kế vẫn phải đúng cho hệ thống thật (NFR-SEC8).

## Quyết định
| Tác vụ | Chính | Dự phòng 1 | Dự phòng 2 | Cuối cùng |
|---|---|---|---|---|
| RAG trợ lý tri thức | **DeepSeek** (API, qua OpenAI-compatible) | **Gemini free tier** | Ollama `qwen2.5` trong cluster | Chỉ trả danh sách tài liệu liên quan |
| Tóm tắt hồ sơ | DeepSeek | Gemini | — | Timeline có cấu trúc (không LLM) |
| Trích xuất tài liệu | **Tesseract (OCR local)** → DeepSeek tách trường | Gemini | — | Hàng đợi người duyệt |
| Chatbot đặt lịch | DeepSeek (tool calling) | Gemini (tool calling) | — | Form đặt lịch thường |
| Embedding | **bge-m3 chạy local** (Ollama, 1024 chiều) | — | — | Chỉ BM25 |

- **Mọi lời gọi tới DeepSeek và Gemini bắt buộc qua redact PII** (không còn cờ `redactPii: false` ngoài Ollama).
- Gemini free tier có hạn mức thấp và dữ liệu có thể được dùng để cải thiện sản phẩm: chỉ là dự phòng, chỉ nhận văn bản đã redact, và chỉ dữ liệu giả.
- Embedding và Ollama chạy trong cluster (node pool `ai`, CPU). Đổi model embedding = index `v2` (không trộn vector).
- Retrieval: hybrid BM25 + vector (RRF) trong AI Search; **không dùng semantic ranker của Azure**. Bước rerank bằng cross-encoder local là tùy chọn, chỉ bật nếu eval (NFR-AI1) cho thấy cần.
- Key DeepSeek/Gemini là secret: Key Vault + CSI trên cloud; local nằm ở `dev/.local/` (không commit), hoặc để trống để chạy bằng Ollama.
- Eval chạy trên **từng** model trong chuỗi (NFR-AI7), kể cả khi DeepSeek/Gemini đổi phiên bản.

## Phương án đã cân nhắc
- DeepSeek trên Azure AI Foundry: dữ liệu ở lại Azure nhưng giá cao hơn và không còn "free"; bị loại theo yêu cầu chi phí.
- Gemini làm model chính: free tier giới hạn lượt gọi quá thấp cho tải RAG; chỉ hợp làm dự phòng.

## Hệ quả
- Rủi ro ổn định/độ trễ của API bên ngoài → giữ chuỗi dự phòng và Ollama cuối chuỗi; NFR-P5/P6 đo cả đường dự phòng.
- Cần node pool `ai` đủ RAM cho Ollama (bge-m3 + qwen2.5-7b ≈ 8–10 GB); trên UAT có thể dùng model nhỏ hơn.
- Bỏ module Azure OpenAI/Document Intelligence khỏi IaC (IAC-19 đổi nội dung).
- Khi bảo vệ cần nêu: hệ thống thật chỉ gửi văn bản đã redact, hoặc dùng model tự host.
