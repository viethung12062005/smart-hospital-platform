"""LLM: giao diện provider, adapter OpenAI-compatible (DeepSeek, Gemini, Ollama), router có fallback (PY-07..09).

Chuỗi model theo tác vụ đọc từ ConfigMap (06-data-ai §4); API key lấy từ biến môi trường do Key Vault cấp.
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
