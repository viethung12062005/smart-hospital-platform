# ml

Mô hình no-show: phục vụ dự đoán, drift, huấn luyện lại tự động. Chủ sở hữu: **D3**.

| Lệnh | Việc |
|---|---|
| `python -m venv .venv && . .venv/bin/activate && pip install -e ".[dev]"` | cài môi trường |
| `ruff check . && ruff format --check . && mypy . && pytest --cov` | đúng các bước CI chạy |
| `uvicorn shp_ml.main:app --reload --port 8090` | chạy local |
| `make -C ../../dev deploy SVC=ml` | build image và deploy lên cụm kind |
