# ai

RAG assistant, record summaries, document extraction, booking chatbot; LLM router with fallback. Owner: **SA** (since 2026-11-01).

| Command | Task |
|---|---|
| `python -m venv .venv && . .venv/bin/activate && pip install -e ".[dev]"` | set up the environment |
| `ruff check . && ruff format --check . && mypy . && pytest --cov` | exactly the CI steps |
| `uvicorn shp_ai.main:app --reload --port 8090` | run locally |
| `make -C ../../dev deploy SVC=ai` | build the image and deploy to the kind cluster |

`shp_platform` in `src/` is a copy of `libs/python` (authentication, errors, CloudEvents, audit, PII redaction, LLM router):
do not edit it here; edit the source and run `python tools/pylib/sync.py`.
