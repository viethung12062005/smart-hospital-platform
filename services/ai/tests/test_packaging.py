"""The image installs the package (`pip install .`), so every data file the code reads must be declared as package data.

Tests import from `src/` and pass even when a file is missing from the installed package; this test closes that gap
(AI-07: the prompt templates were left out of the image and every prompt-based feature failed in the cluster).
"""

import fnmatch
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def _declared(package: str) -> list[str]:
    config = tomllib.loads((ROOT / "pyproject.toml").read_text(encoding="utf-8"))
    return list(config["tool"]["setuptools"].get("package-data", {}).get(package, []))


def test_every_data_file_of_shp_ai_is_package_data() -> None:
    package = ROOT / "src" / "shp_ai"
    patterns = _declared("shp_ai")
    data_files = [
        p.relative_to(package).as_posix()
        for p in package.rglob("*")
        if p.is_file() and p.suffix not in {".py", ".pyc"} and "__pycache__" not in p.parts
    ]
    assert data_files, "expected the prompt templates under src/shp_ai/prompts"
    missing = [f for f in data_files if not any(fnmatch.fnmatch(f, pat) for pat in patterns)]
    assert missing == [], f"not declared in [tool.setuptools.package-data] shp_ai: {missing}"


def test_every_prompt_task_has_a_template() -> None:
    prompts = ROOT / "src" / "shp_ai" / "prompts"
    tasks = sorted(d.name for d in prompts.iterdir() if d.is_dir() and d.name != "__pycache__")
    assert tasks == ["booking-agent", "patient-summary", "rag-answer"]
    assert all(any((prompts / t).glob("v*.md")) for t in tasks)
