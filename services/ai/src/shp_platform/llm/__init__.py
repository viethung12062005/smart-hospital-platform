"""LLM: provider interface, OpenAI-compatible adapter (DeepSeek, Gemini, Ollama), router with fallback (PY-07..09).

Per-task model chains are read from the ConfigMap (06-data-ai §4); API keys come from environment variables
supplied by Key Vault.
"""

from shp_platform.llm.provider import ChatMessage, Completion, FakeProvider, LlmError, Provider, ToolCall
from shp_platform.llm.router import ChainStep, Router, RouterResult, TaskConfig

__all__ = [
    "ChainStep",
    "ChatMessage",
    "Completion",
    "FakeProvider",
    "LlmError",
    "Provider",
    "Router",
    "RouterResult",
    "TaskConfig",
    "ToolCall",
]
