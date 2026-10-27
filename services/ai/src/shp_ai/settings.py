from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    service_name: str = "ai"
    version: str = "0.1.0"
    git_sha: str = "unknown"
