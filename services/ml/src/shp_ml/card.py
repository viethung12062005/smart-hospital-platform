"""Model card auto-generated at registration (ML-18, 16-mlops §3)."""

from typing import Any


def _fmt(v: Any) -> str:
    return "—" if v is None else (f"{v:.4f}" if isinstance(v, float) else str(v))


def model_card(meta: dict[str, Any]) -> str:
    m = meta["metrics"]
    groups = "\n".join(
        f"| {col} | {g} | {_fmt(v)} |" for col, gs in m.get("subgroupAuc", {}).items() for g, v in gs.items()
    )
    checks = "\n".join(
        f"| {c['name']} | {_fmt(c['value'])} | {_fmt(c['limit'])} | {'pass' if c['passed'] else 'FAIL'} |"
        for c in meta["gate"]["checks"]
    )
    return (
        f"""# Model card: shp-no-show

> **Warning:** trained on **synthetic data** (services/ml/src/shp_ml/synth.py). The results demonstrate """
        f"""the MLOps process,
> not predictive ability on real patients.

## Purpose and limitations of use
- Estimate the probability that a patient will miss (no-show) an appointment, so an extra reminder can be sent (US-046).
- **Not** for refusing, deprioritizing or charging patients; not for clinical decisions.
- Features contain no PII: only time, specialty, channel, 10-year age band, prior visit statistics.

## Data
| Item | Value |
|---|---|
| Hash dataset (sha256) | `{meta["datasetSha256"]}` |
| Train | {m["train"]["n"]} appointments, {m["train"]["from"]} → {m["train"]["to"]} |
| Validation (time-based) | {m["validation"]["n"]} appointments, {m["validation"]["from"]} → {m["validation"]["to"]} |
| No-show rate (validation) | {_fmt(m["positiveRate"])} |

## Results
| Metric | Value |
|---|---|
| AUC | {_fmt(m["auc"])} |
| AUC baseline (Logistic Regression) | {_fmt(m["baselineAuc"])} |
| Production model AUC at registration | {_fmt(m.get("productionAuc"))} |
| ECE (calibration) | {_fmt(m["ece"])} |
| p95 prediction latency for one appointment (ms) | {_fmt(m["latencyP95Ms"])} |
| High-risk threshold | {_fmt(m["threshold"])} (largest recall with precision ≥ 0.5 on the tuning set) |
| Precision / recall at the threshold | {_fmt(m["precisionAtThreshold"])} / {_fmt(m["recallAtThreshold"])} |

### AUC by subgroup (groups ≥ 300 samples)
| Group | Value | AUC |
|---|---|---|
{groups}

## Quality gates
| Check | Value | Limit | Result |
|---|---|---|---|
{checks}

## Provenance
- Params: `{meta["params"]}`, seed {meta["seed"]}
- Run: `{meta["runId"]}` · triggered by: {meta.get("trigger", "manual")} · registered at {meta["registeredAt"]}
- Process: services/ml/src/shp_ml/pipeline.py (16-mlops §2-3)
"""
    )
