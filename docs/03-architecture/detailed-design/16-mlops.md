# 16 — MLOps for the no-show prediction model

Model, features and data tables: [04](04-db-notification-ml.md) §2. Original decision: [ADR-0007](../adr/0007-no-show-ml-mlops.md).
Story: US-063..065, US-071..074 · Task: `ML-*`, `MLO-*` (SA).

## 1. Training data (entirely synthetic data)

We do not use public no-show datasets because they originate from real patient data. SA writes the data generator per the following specification (`ML-01`, `ML-21`).

| Item | Value |
|---|---|
| Scale | ~80,000 appointments, 24 months, ~50,000 synthetic patients (random IDs, unrelated to real people) |
| Label | `noshow` (1) / `fulfilled` (0); `cancelled` appointments are removed from the training set |
| Positive rate | ~18% overall, varying by month (±3 percentage points) |
| Rule | `logit(p) = β0 + β1·lead_time + β2·prior_noshow_rate + β3·is_first_visit + β4·early_morning + β5·channel + β6·specialty + ε`; coefficients chosen by SA and recorded in the document, **ε large enough for AUC to reach about 0.78–0.82** (otherwise the AUC ≥ 0.75 gate is meaningless); add threshold effects (lead time < 1 day, > 14 days) and interactions (early morning × age 60+, chatbot × age 10–29) so the non-linear model can beat the linear baseline (coefficients: services/ml/README.md) |
| Temporal structure | Has a seasonal and disease-season trend; split train/validation **by time** (first 18 months / last 6 months), with no shuffling |
| Reproducibility | Fixed seed; produces Parquet + a description file (schema, label rate, hash) |
| Shifted dataset (`ML-21`) | Same schema; mean lead time +40%, no-show rate +6 points, changed booking channel weights, early-morning appointments (07:00–07:45) from 10% to 35% (so that ≥ 2 main features exceed PSI 0.2; lead time +40% alone gives only PSI ≈ 0.08). Used to test the drift detector and demo the alert |
| Limitation that must be stated in the report | The model only relearns rules written by the team; the results demonstrate the MLOps process, **not** real predictive capability |

## 2. Controlled and reproducible training

```
Parquet dataset (hash) ─► data validation (pandera) ─► shared feature package ─► train (baseline LR, LightGBM)
        ─► MLflow run (parameters, metrics, dataset hash, git SHA, image digest) ─► quality gate ─► register candidate
```

- **Data versioning:** `ML-13`. Each MLflow run records the dataset hash; the same hash + seed + image must produce the same model.
- **Data validation (`ML-14`):** schema, types, value ranges, missing rate < 2%, labels with no temporal leakage (features only use information available before `booked_at`), positive rate within [10%, 30%]. Failure → stop the pipeline, do not train.
- **Shared features (`ML-15`, `ML-16`):** one package for both training and serving; a test compares outputs on the reference sample set and they must match exactly.
- **Environment (`MLO-02`):** a version-pinned, signed, vulnerability-scanned training image.

## 3. Quality gate and model card

A candidate model is registered (alias `candidate`) only when **all** of the following conditions hold:

| Gate | Condition |
|---|---|
| Overall performance | AUC (time-based validation) ≥ 0.75 and better than the LR baseline |
| Subgroups | AUC of each specialty group, age group, booking channel (groups with ≥ 300 samples) is not more than 0.07 below the overall AUC |
| Calibration | Expected calibration error (ECE) ≤ 0.05 |
| Latency | p95 prediction ≤ 100 ms on the deployment configuration |
| No regression | Not more than 0.01 AUC worse than the current `production` model on the same test set |

**Model card** (`ML-18`) auto-generated at each registration: intended use and limitations, data (hash, time range, label rate), overall and per-group metrics, threshold and how it was chosen, code/image version, author/process, warning "trained on synthetic data".

## 4. Monitoring: drift and actual performance

| Signal | How it is computed | Alert threshold |
|---|---|---|
| Feature drift (`ML-19`) | PSI per feature, last 7-day window compared with the training set | PSI > 0.2 in ≥ 2 main features (`lead_time_days`, `prior_noshow`, `start_hour`, `channel`) |
| Prediction score drift | PSI of the `score` distribution compared with registration time | PSI > 0.2 |
| Actual performance (`ML-20`) | Join `prediction` with the real outcome from `appointment-status-changed`; 14-day rolling AUC | AUC drop > 0.05 compared with registration time **and** ≥ 500 labeled samples (labels arrive late because the appointment must take place first) |
| Operations | Latency, error rate, `unknown` rate of ageBand | per the common SLO |

The job runs daily (`ML-10`) and exports Prometheus metrics; alerts and dashboards are in `MLO-05`.

## 5. Automatic retraining

```
drift alert / AUC drop ─► ML-22 checks the conditions ─► MLO-04 runs job ML-23 on the cluster
     ─► data validation ─► train ─► quality gate (§3) ─► register alias candidate ─► (§6) shadow + canary
```

| Rule | Value |
|---|---|
| Run condition | A drift alert **or** an actual AUC drop; ≥ 500 new labeled samples since the previous training; outside the **7-day** cooldown |
| Concurrency guard | At most one retraining at a time (lock) |
| Training data | Sliding window: the most recent 24 months including new labeled data |
| Gate not passed | Stop, **keep the production model**, alert with the reason (which gate, what value) |
| Gate passed | Register `candidate`, continue to §6; every step writes audit and links the MLflow run |
| Safety limit | At most 2 automatic retrainings every 14 days; beyond that, stop and notify the operator |

## 6. Deployment with shadow and automatic canary

| Step | Behavior | Condition to move to the next step |
|---|---|---|
| Shadow (`ML-24`) | The `candidate` model scores in parallel with `production`, results are only recorded, users are unaffected | ≥ 1,000 predictions or 24 hours; score PSI between the two models < 0.1; classification agreement (`highRisk`) ≥ 90% |
| Canary 10% | Argo Rollouts shifts 10% of prediction traffic to `candidate` | AnalysisTemplate passes within 30 minutes |
| Canary 50% | Increase to 50% | AnalysisTemplate passes within 30 minutes |
| 100% | Move the `production` alias to the new model; keep the old model as the rollback point | — |

**AnalysisTemplate (`MLO-03`)** reads Prometheus metrics by `model_version` (`ML-25`): error rate ≤ the old version + 0.5%, p95 latency ≤ 100 ms, score PSI < 0.1, `highRisk` rate within ±5 percentage points of the old version. **Violation of any metric → automatic rollback** to the old `production` alias and an alert.

`autoPromote` is configured per environment: `true` in local/UAT (runs through all steps); in prod the default is `true` because the data is synthetic and the risk is low, set `false` if you want to stop and wait for approval (GitHub Environment) before the 100% step.

## 7. Mapping against the assignment brief

The AI Engineering part (P5) requires an evaluation dataset/metrics, guardrails, latency/cost: satisfied by §1–§4 (evaluation, per-group quality), §6 (latency, rollback) and the model card (§3). Measurement and reporting of AUC, drift, and retraining history are in the PRD-07 report.
