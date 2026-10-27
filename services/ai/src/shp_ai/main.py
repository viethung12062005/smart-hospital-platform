from fastapi import FastAPI

from shp_ai.settings import Settings

settings = Settings()
app = FastAPI(title="shp-ai", version=settings.version)


@app.get("/health/live")
def live() -> dict[str, str]:
    return {"status": "Healthy"}


@app.get("/health/ready")
def ready() -> dict[str, str]:
    # TODO(dev): kiểm kết nối phụ thuộc (DB, broker) khi thêm vào.
    return {"status": "Healthy"}


@app.get("/info")
def info() -> dict[str, str]:
    return {"name": settings.service_name, "version": settings.version, "gitSha": settings.git_sha}


# TODO(dev): thêm router nghiệp vụ theo docs/03-architecture/detailed-design (một module/nhóm endpoint).
