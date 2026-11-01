from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Cấu hình từ biến môi trường (Helm values). Không có secret: Mongo/Service Bus dùng workload identity ở cloud."""

    service_name: str = "ml"
    version: str = "0.1.0"
    git_sha: str = "unknown"

    mongodb_uri: str = ""  # local: mongodb://localhost:27117/?directConnection=true
    mongodb_db: str = "ml"
    rabbitmq_url: str = ""  # local
    servicebus_namespace: str = ""  # cloud: <ns>.servicebus.windows.net
    consumer_name: str = "ml"

    registry_dir: str = ""  # FileRegistry (local/CI)
    mlflow_tracking_uri: str = ""  # cụm
    model_poll_seconds: float = 30.0  # đổi alias production → nạp lại trong ≤ 60 giây (ML-09)
    shadow_enabled: bool = True  # candidate chấm điểm song song khi khác production (ML-24)

    predict_callers: str = "svc-scheduling,svc-ci"  # azp được gọi M3

    def callers(self) -> list[str]:
        return [c.strip() for c in self.predict_callers.split(",") if c.strip()]
