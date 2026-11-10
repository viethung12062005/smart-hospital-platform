"""Versioned prompt templates (AI-07): `prompts/<task>/v<N>.md`; ID `<task>/v<N>` recorded in messages for
traceability."""

from functools import cache
from importlib import resources

CURRENT = {"rag-answer": "v1", "patient-summary": "v1", "extraction": "v1", "booking-agent": "v1"}


@cache
def load(task: str, version: str | None = None) -> tuple[str, str]:
    v = version or CURRENT[task]
    text = resources.files("shp_ai.prompts").joinpath(task, f"{v}.md").read_text(encoding="utf-8")
    return f"{task}/{v}", text
