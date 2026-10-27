# ai

Trợ lý RAG, tóm tắt hồ sơ, trích xuất tài liệu, chatbot đặt lịch; LLM router có fallback. Chủ sở hữu: **D3**.

| Lệnh | Việc |
|---|---|
| `python -m venv .venv && . .venv/bin/activate && pip install -e ".[dev]"` | cài môi trường |
| `ruff check . && ruff format --check . && mypy . && pytest --cov` | đúng các bước CI chạy |
| `uvicorn shp_ai.main:app --reload --port 8090` | chạy local |
| `make -C ../../dev deploy SVC=ai` | build image và deploy lên cụm kind |
