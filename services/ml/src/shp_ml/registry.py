"""Model registry và ghi run (ML-05, ML-13; ADR-0007).

Hai bản cùng giao diện:
- `FileRegistry`: thư mục cục bộ (dev, test, CI). models/<version>/{model.txt, meta.json, card.md}, aliases.json.
- `MlflowRegistry`: MLflow tracking + model registry (cụm). Cần extra `mlflow`; model lưu dạng artifact văn bản.
Alias: `candidate` (job huấn luyện được đặt) và `production` (chỉ quy trình shadow/canary hoặc người được đổi).
"""

import hashlib
import json
import os
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

from shp_ml.model import MODEL_NAME, NoShowModel

ALIASES = frozenset({"candidate", "production", "previous-production"})


@dataclass(frozen=True)
class Registered:
    version: str
    meta: dict[str, Any]


class Registry(Protocol):
    def register(self, model_text: str, meta: dict[str, Any], card: str) -> str: ...

    def set_alias(self, alias: str, version: str) -> None: ...

    def alias_version(self, alias: str) -> str | None: ...

    def load(self, version: str) -> tuple[NoShowModel, dict[str, Any]]: ...

    def log_run(self, run: dict[str, Any]) -> str: ...


def _atomic_write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=".tmp-")
    with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    os.replace(tmp, path)


class FileRegistry:
    def __init__(self, root: Path) -> None:
        self.root = root

    def _versions(self) -> list[int]:
        d = self.root / "models"
        return sorted(int(p.name) for p in d.iterdir() if p.name.isdigit()) if d.is_dir() else []

    def register(self, model_text: str, meta: dict[str, Any], card: str) -> str:
        version = str((self._versions() or [0])[-1] + 1)
        full = {
            **meta,
            "modelName": MODEL_NAME,
            "modelVersion": version,
            "modelSha256": hashlib.sha256(model_text.encode()).hexdigest(),
        }
        body = json.dumps(full, ensure_ascii=False, indent=2)  # lỗi tuần tự hóa xảy ra trước khi ghi gì
        (self.root / "models").mkdir(parents=True, exist_ok=True)
        tmp = Path(tempfile.mkdtemp(dir=self.root / "models", prefix=".tmp-"))
        _atomic_write(tmp / "model.txt", model_text)
        _atomic_write(tmp / "card.md", card)
        _atomic_write(tmp / "meta.json", body)
        os.replace(tmp, self.root / "models" / version)  # version chỉ xuất hiện khi đủ ba tệp
        return version

    def _aliases(self) -> dict[str, str]:
        p = self.root / "aliases.json"
        data: dict[str, str] = json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}
        return data

    def set_alias(self, alias: str, version: str) -> None:
        if alias not in ALIASES:
            raise ValueError(f"alias không hợp lệ: {alias}")
        if not (self.root / "models" / version / "model.txt").exists():
            raise ValueError(f"version chưa đăng ký: {version}")
        a = self._aliases()
        a[alias] = version
        _atomic_write(self.root / "aliases.json", json.dumps(a, indent=2))

    def alias_version(self, alias: str) -> str | None:
        return self._aliases().get(alias)

    def load(self, version: str) -> tuple[NoShowModel, dict[str, Any]]:
        d = self.root / "models" / version
        meta: dict[str, Any] = json.loads((d / "meta.json").read_text(encoding="utf-8"))
        text = (d / "model.txt").read_text(encoding="utf-8")
        return NoShowModel.from_text(text, version, float(meta["threshold"]), meta["modelSha256"]), meta

    def log_run(self, run: dict[str, Any]) -> str:
        run_id = hashlib.sha256(json.dumps(run, sort_keys=True, default=str).encode()).hexdigest()[:16]
        _atomic_write(self.root / "runs" / f"{run_id}.json", json.dumps(run, ensure_ascii=False, indent=2, default=str))
        return run_id


class MlflowRegistry:  # pragma: no cover - cần MLflow server; thử ở môi trường cụm (LOC-08)
    """MLflow: run ghi tham số/metric/hash dataset; model là artifact văn bản; alias qua model registry."""

    def __init__(self, tracking_uri: str, experiment: str = "shp-no-show") -> None:
        import mlflow  # extra "mlflow", không có trong image phục vụ

        self._mlflow = mlflow
        mlflow.set_tracking_uri(tracking_uri)
        mlflow.set_experiment(experiment)
        self._client = mlflow.MlflowClient()

    def log_run(self, run: dict[str, Any]) -> str:
        with self._mlflow.start_run() as r:
            self._mlflow.log_params({k: v for k, v in run.get("params", {}).items()})
            self._mlflow.log_metrics({k: float(v) for k, v in run.get("metrics", {}).items()})
            self._mlflow.set_tags({k: str(v) for k, v in run.get("tags", {}).items()})
            self._mlflow.log_dict(run, "run.json")
            return str(r.info.run_id)

    def register(self, model_text: str, meta: dict[str, Any], card: str) -> str:
        with self._mlflow.start_run(run_id=meta.get("runId")) as r:
            self._mlflow.log_text(model_text, "model/model.txt")
            self._mlflow.log_text(card, "model/card.md")
            full = {**meta, "modelSha256": hashlib.sha256(model_text.encode()).hexdigest()}
            self._mlflow.log_dict(full, "model/meta.json")
            mv = self._mlflow.register_model(f"runs:/{r.info.run_id}/model", MODEL_NAME)
        return str(mv.version)

    def set_alias(self, alias: str, version: str) -> None:
        if alias not in ALIASES:
            raise ValueError(f"alias không hợp lệ: {alias}")
        self._client.set_registered_model_alias(MODEL_NAME, alias, version)

    def alias_version(self, alias: str) -> str | None:
        try:
            return str(self._client.get_model_version_by_alias(MODEL_NAME, alias).version)
        except Exception:  # noqa: BLE001 - alias chưa có
            return None

    def load(self, version: str) -> tuple[NoShowModel, dict[str, Any]]:
        mv = self._client.get_model_version(MODEL_NAME, version)
        base = f"runs:/{mv.run_id}/model"
        meta: dict[str, Any] = self._mlflow.artifacts.load_dict(f"{base}/meta.json")
        text = self._mlflow.artifacts.load_text(f"{base}/model.txt")
        return NoShowModel.from_text(text, version, float(meta["threshold"]), meta["modelSha256"]), meta
