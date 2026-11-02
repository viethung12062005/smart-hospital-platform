from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Cấu hình từ biến môi trường (Helm values). API key LLM từ Key Vault (CSI), không có trong ConfigMap hay repo."""

    service_name: str = "ai"
    version: str = "0.1.0"
    git_sha: str = "unknown"

    mongodb_uri: str = ""
    mongodb_db: str = "ai"
    rabbitmq_url: str = ""
    servicebus_namespace: str = ""
    consumer_name: str = "ai"

    # tìm kiếm: local OpenSearch, cloud Azure AI Search (managed identity)
    opensearch_url: str = ""
    azure_search_endpoint: str = ""
    search_index: str = "kb-chunks-v1"
    # embedding + LLM local
    ollama_url: str = ""
    embedding_model: str = "bge-m3"
    embedding_dimensions: int = 1024
    # Blob: local Azurite (connection string), cloud account URL + managed identity
    azure_storage_connection_string: str = ""
    azure_storage_account_url: str = ""
    kb_container: str = "kb"
    ocr_container: str = "ai-ocr"

    # LLM provider ngoài Azure (ADR-0013); key trống thì provider không được dùng
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com/v1"
    gemini_api_key: str = ""
    gemini_base_url: str = "https://generativelanguage.googleapis.com/v1beta/openai"
    llm_config_path: str = ""  # ConfigMap: chuỗi model theo tác vụ (06 §4); trống → cấu hình mặc định trong mã

    # gọi service khác
    clinical_url: str = "http://clinical:8080"
    scheduling_url: str = "http://scheduling:8080"
    document_url: str = "http://document:8080"
    token_endpoint: str = ""  # <issuer>/protocol/openid-connect/token
    client_id: str = "svc-ai"
    client_secret: str = ""

    daily_token_limit: int = 2_000_000
