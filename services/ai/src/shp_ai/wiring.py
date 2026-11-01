"""Dựng phụ thuộc thật từ cấu hình và chạy tác vụ nền (consumer, relay outbox). Thiếu cấu hình thì dùng bản cục bộ
(ghi cảnh báo) để dev chạy được; ở UAT/prod Helm values đặt đủ."""

import logging
import threading
from typing import Any

from shp_ai import usage
from shp_ai.assistant import conversations
from shp_ai.blobstore import AzureBlobStore, BlobStore, MemoryBlobStore
from shp_ai.deps import Deps
from shp_ai.directory import ClinicalDirectory, Directory, StaticDirectory
from shp_ai.kb.embedding import Embedder, HashingEmbedder, OllamaEmbedder
from shp_ai.kb.index import AzureSearchIndex, MemoryIndex, OpenSearchIndex, SearchIndex
from shp_ai.kb.service import ensure_indexes
from shp_ai.llm_config import build_router, load_config, providers_from
from shp_ai.settings import Settings
from shp_ai.usage import MongoTokenBudget
from shp_platform import mongo
from shp_platform.bus import Bus, RabbitBus, ServiceBus
from shp_platform.service_token import ServiceTokenProvider, StaticToken

LOG = logging.getLogger("shp.ai.wiring")
STARTERS: list[Any] = []  # (deps, bus, stop) -> None: consumer của từng tính năng


def from_settings(s: Settings) -> Deps:  # pragma: no cover - hạ tầng thật
    from pymongo import MongoClient

    blobs: BlobStore
    if s.azure_storage_connection_string or s.azure_storage_account_url:
        blobs = AzureBlobStore(s.azure_storage_connection_string, s.azure_storage_account_url)
    else:
        LOG.warning("không có Blob: dùng bộ nhớ (chỉ dev)")
        blobs = MemoryBlobStore()
    index: SearchIndex
    if s.azure_search_endpoint:
        index = AzureSearchIndex(s.azure_search_endpoint, s.search_index, s.embedding_dimensions)
    elif s.opensearch_url:
        index = OpenSearchIndex(s.opensearch_url, s.search_index, s.embedding_dimensions)
    else:
        LOG.warning("không có index tìm kiếm: dùng bộ nhớ (chỉ dev)")
        index = MemoryIndex()
    embedder: Embedder = (
        OllamaEmbedder(s.ollama_url, s.embedding_model, s.embedding_dimensions)
        if s.ollama_url
        else HashingEmbedder(s.embedding_dimensions)
    )
    tokens = ServiceTokenProvider(s.token_endpoint, s.client_id, s.client_secret) if s.token_endpoint else StaticToken()
    directory: Directory = ClinicalDirectory(s.clinical_url, tokens) if s.token_endpoint else StaticDirectory()
    config = load_config(s.llm_config_path)
    client: MongoClient[dict[str, Any]] = MongoClient(s.mongodb_uri, tz_aware=True)
    budget = MongoTokenBudget(client[s.mongodb_db], s.daily_token_limit)  # chung cho mọi replica
    router = build_router(config, providers_from(s), budget, usage.on_call)
    return Deps(s, client, blobs, index, embedder, directory, router, {"llm_config": config, "service_tokens": tokens})


def offline_deps(s: Settings) -> Deps:
    """Không có MongoDB: chỉ phục vụ endpoint nền tảng (health, info, metrics)."""
    config = load_config("")
    return Deps(
        s,
        None,
        MemoryBlobStore(),
        MemoryIndex(),
        HashingEmbedder(s.embedding_dimensions),
        StaticDirectory(),
        build_router(config, [], None),
        {"llm_config": config},
    )


def make_bus(s: Settings) -> Bus | None:  # pragma: no cover
    if s.servicebus_namespace:
        return ServiceBus(s.servicebus_namespace)
    if s.rabbitmq_url:
        return RabbitBus(s.rabbitmq_url)
    return None


def start_background(deps: Deps, stop: threading.Event) -> None:  # pragma: no cover - hạ tầng thật
    ensure_indexes(deps.db)
    conversations.ensure_indexes(deps.db)
    usage.ensure_indexes(deps.db)
    mongo.ensure_messaging_indexes(deps.db)
    try:
        deps.index.ensure()
    except Exception as exc:  # noqa: BLE001 - index chưa sẵn sàng: truy xuất sẽ báo lỗi, service vẫn chạy
        LOG.warning("không tạo được index: %s", type(exc).__name__)
    bus = make_bus(deps.settings)
    if bus is None:
        return
    for start in STARTERS:
        start(deps, bus, stop)

    def relay() -> None:
        while not stop.wait(1.0):
            try:
                mongo.relay_once(deps.db, bus)
            except Exception as exc:  # noqa: BLE001
                LOG.warning("relay outbox lỗi: %s", type(exc).__name__)

    threading.Thread(target=relay, name="outbox-relay", daemon=True).start()
