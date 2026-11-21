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
    # Model canary (MLO-03, 16-mlops section 6): when set, the pod serves exactly this registry version instead of the
    # `production` alias, so a model change is a pod template change that Argo Rollouts can canary and abort.
    # Empty = follow the `production` alias (local, tests).
    serving_model_version: str = ""
    shadow_enabled: bool = True  # candidate scores in parallel when it differs from production (ML-24)
    # Score PSI between candidate (shadow) and production on the same appointments (ML-25, 16-mlops section 6)
    shadow_psi_window: int = 1000  # last N paired scores
    shadow_psi_min_pairs: int = 200  # fewer pairs give a noisy PSI: the metric is not exported until then

    predict_callers: str = "svc-scheduling,svc-ci"  # azp values allowed to call M3

    def callers(self) -> list[str]:
        return [c.strip() for c in self.predict_callers.split(",") if c.strip()]
