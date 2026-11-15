from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Configuration from environment variables (Helm values). No secrets: Mongo/Service Bus use workload identity in
    the cloud."""

    service_name: str = "ml"
    version: str = "0.1.0"
    git_sha: str = "unknown"

    mongodb_uri: str = ""  # local: mongodb://localhost:27117/?directConnection=true
    mongodb_db: str = "ml"
    rabbitmq_url: str = ""  # local
    servicebus_namespace: str = ""  # cloud: <ns>.servicebus.windows.net
    consumer_name: str = "ml"

    registry_dir: str = ""  # FileRegistry (local/CI)
    mlflow_tracking_uri: str = ""  # cluster
    model_poll_seconds: float = 30.0  # production alias change → reload within ≤ 60 seconds (ML-09)
    shadow_enabled: bool = True  # candidate scores in parallel when it differs from production (ML-24)

    predict_callers: str = "svc-scheduling,svc-ci"  # azp values allowed to call M3

    def callers(self) -> list[str]:
        return [c.strip() for c in self.predict_callers.split(",") if c.strip()]
