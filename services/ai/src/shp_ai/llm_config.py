"""Build the LLM router from configuration (06 §4, ADR-0013). The default chains match the docs; a ConfigMap (YAML)
can override them.

A provider missing its API key is dropped from every chain (it cannot be called). Ollama runs in-cluster so
`external=False`; DeepSeek and Gemini are outside Azure so they always receive PII-redacted text.
"""

from pathlib import Path
from typing import Any

import yaml

from shp_ai.settings import Settings
from shp_platform.llm import ChainStep, Provider, Router, TaskConfig
from shp_platform.llm.provider import OpenAICompatibleProvider
from shp_platform.llm.router import CircuitBreaker, TokenBudget

DEFAULT: dict[str, Any] = {
    "tasks": {
        "rag-answer": {
            "timeoutSeconds": 12,
            "maxOutputTokens": 800,
            "finalFallback": "retrieval-only",
            "chain": [
                {"provider": "deepseek", "model": "deepseek-chat"},
                {"provider": "gemini", "model": "gemini-2.0-flash"},
                {"provider": "ollama", "model": "qwen2.5:7b-instruct", "redactPii": False},
            ],
        },
        "patient-summary": {
            "timeoutSeconds": 15,
            "maxOutputTokens": 900,
            "finalFallback": "structured-timeline",
            "chain": [
                {"provider": "deepseek", "model": "deepseek-chat"},
                {"provider": "gemini", "model": "gemini-2.0-flash"},
            ],
        },
        "extraction": {
            "timeoutSeconds": 20,
            "maxOutputTokens": 900,
            "finalFallback": "human-review",
            "chain": [
                {"provider": "deepseek", "model": "deepseek-chat"},
                {"provider": "gemini", "model": "gemini-2.0-flash"},
            ],
        },
        "booking-agent": {
            "timeoutSeconds": 10,
            "maxOutputTokens": 400,
            "finalFallback": "booking-form",
            "chain": [
                {"provider": "deepseek", "model": "deepseek-chat"},
                {"provider": "gemini", "model": "gemini-2.0-flash"},
            ],
        },
    },
    "circuitBreaker": {"failureThreshold": 5, "windowSeconds": 60, "openSeconds": 30},
    # USD per 1 million tokens (input, output) for cost estimates (AI-13); update with the price list at deployment
    "pricing": {"deepseek": [0.27, 1.10], "gemini": [0.10, 0.40], "ollama": [0.0, 0.0]},
}


def load_config(path: str) -> dict[str, Any]:
    if not path:
        return DEFAULT
    data = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    return {**DEFAULT, **(data or {})}


def providers_from(settings: Settings) -> list[Provider]:
    out: list[Provider] = []
    if settings.deepseek_api_key:
        out.append(OpenAICompatibleProvider("deepseek", settings.deepseek_base_url, settings.deepseek_api_key, True))
    if settings.gemini_api_key:
        out.append(OpenAICompatibleProvider("gemini", settings.gemini_base_url, settings.gemini_api_key, True))
    if settings.ollama_url:
        out.append(OpenAICompatibleProvider("ollama", settings.ollama_url.rstrip("/") + "/v1", "", False))
    return out


def build_router(
    config: dict[str, Any], providers: list[Provider], budget: TokenBudget | None, on_call: Any = None
) -> Router:
    names = {p.name for p in providers}
    tasks = {}
    for name, t in config["tasks"].items():
        chain = [
            ChainStep(s["provider"], s["model"], bool(s.get("redactPii", True)))
            for s in t["chain"]
            if s["provider"] in names
        ]
        tasks[name] = TaskConfig(
            chain, t["finalFallback"], float(t.get("timeoutSeconds", 12)), int(t.get("maxOutputTokens", 800))
        )
    cb = config.get("circuitBreaker", {})
    breaker = CircuitBreaker(
        int(cb.get("failureThreshold", 5)), float(cb.get("windowSeconds", 60)), float(cb.get("openSeconds", 30))
    )
    return Router(providers, tasks, breaker, budget, on_call)


def cost_usd(config: dict[str, Any], provider: str, tokens_in: int, tokens_out: int) -> float:
    price = config.get("pricing", {}).get(provider, [0.0, 0.0])
    return round((tokens_in * float(price[0]) + tokens_out * float(price[1])) / 1_000_000, 6)
