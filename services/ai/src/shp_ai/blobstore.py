"""Kho file: Azure Blob (cloud: account URL + managed identity; local: Azurite qua connection string) và bản bộ nhớ."""

from typing import Protocol


class BlobStore(Protocol):
    def put(self, container: str, path: str, data: bytes, content_type: str) -> None: ...

    def get(self, container: str, path: str) -> bytes: ...


class MemoryBlobStore:
    def __init__(self) -> None:
        self.items: dict[tuple[str, str], tuple[bytes, str]] = {}

    def put(self, container: str, path: str, data: bytes, content_type: str) -> None:
        self.items[(container, path)] = (data, content_type)

    def get(self, container: str, path: str) -> bytes:
        return self.items[(container, path)][0]


class AzureBlobStore:  # pragma: no cover - cần Azurite/Azure; thử ở cụm local
    def __init__(self, connection_string: str = "", account_url: str = "") -> None:
        from azure.storage.blob import BlobServiceClient

        if connection_string:
            self._svc = BlobServiceClient.from_connection_string(connection_string)
        else:
            from azure.identity import DefaultAzureCredential

            self._svc = BlobServiceClient(account_url, credential=DefaultAzureCredential())
        self._ready: set[str] = set()

    def _container(self, name: str):  # type: ignore[no-untyped-def]
        c = self._svc.get_container_client(name)
        if name not in self._ready:
            if not c.exists():
                c.create_container()
            self._ready.add(name)
        return c

    def put(self, container: str, path: str, data: bytes, content_type: str) -> None:
        from azure.storage.blob import ContentSettings

        self._container(container).upload_blob(
            path, data, overwrite=False, content_settings=ContentSettings(content_type=content_type)
        )

    def get(self, container: str, path: str) -> bytes:
        data: bytes = self._container(container).download_blob(path).readall()
        return data
