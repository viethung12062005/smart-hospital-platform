# ml

No-show model: prediction serving, drift, automatic retraining. Owner: **SA** (since 2026-11-01).

| Command | Task |
|---|---|
| `python -m venv .venv && . .venv/bin/activate && pip install -e ".[dev]"` | set up the environment |
| `ruff check . && ruff format --check . && mypy . && pytest --cov` | exactly the CI steps |
| `uvicorn shp_ml.main:app --reload --port 8090` | run locally |
| `make -C ../../dev deploy SVC=ml` | build the image and deploy to the kind cluster |

## Synthetic training data and baseline (ML-01, ML-02, ML-14, ML-15, ML-21, ML-03)

The new system has no operational history yet, so the first model learns from **synthetic data** produced by `shp_ml.synth` following
[16-mlops §1](../../docs/03-architecture/detailed-design/16-mlops.md). In real operation, the consumer (ML-06) records
`appointment_facts` from scheduling events; the retraining pipeline (ML-22) uses exactly that data with the same feature package.

| Command | Task |
|---|---|
| `python -m shp_ml.synth --out data/noshow.parquet` | 80,000 appointments, 24 months, ~50,000 synthetic patients, seed 42; plus `noshow.json` (hash, label rates) |
| `python -m shp_ml.synth --out data/shifted.parquet --shifted` | shifted set for drift tests: lead time +40%, no-show +6 points, more chatbot |
| `python -m shp_ml.train --data data/noshow.parquet --out runs/baseline` | Logistic Regression baseline only (quick comparison) |

Data-generating rules (coefficients in `synth.Coef`): logit = β0 + 0.1·lead_time/7 − 1.3·(booked within 1 day) + 1.4·(booked > 14 days ahead)
+ 1.1·logit(smoothed no-show rate) + 0.35·first visit + 0.15·visit before 8 am + 1.6·(before 8 am and aged 60+)
+ channel (staff −0.45, chatbot +0.3) + 1.4·(chatbot and age 10–29) + specialty (dermatology +0.35, pediatrics −0.25) + season (peak in January)
+ latent patient propensity (sd 0.9, not a feature). β0 is calibrated to a rate ≈ 18%.
The threshold effects and interactions are intentional: real behavior is non-linear, and if the data were purely linear LR would always win,
making the "beats baseline" gate meaningless.

Seed 42 results (SA machine, 2026-11-01): no-show 18.0%; LR baseline AUC **0.769**; LightGBM AUC **0.784**, ECE 0.019,
p95 prediction latency 6.7 ms, every subgroup passes; the auto-selected high-risk threshold is 0.31.

### Training, quality gates, registration (ML-03..05, ML-13, ML-17, ML-18)

| Command | Task |
|---|---|
| `python -m shp_ml.pipeline --data data/noshow.parquet --registry runs/registry --promote-first` | full pipeline with a directory registry; `--promote-first` is only for the first bootstrap |
| `pip install -e ".[train,mlflow]"` then `python -m shp_ml.pipeline --data ... --mlflow http://mlflow:5000` | same pipeline, logging to MLflow (cluster) |

The model is stored as LightGBM text (no pickle) with sha256, re-verified on load. The training job only sets the `candidate` alias;
`production` changes only through shadow/canary (ML-24) or an authorized person. Gate failure: nothing is registered, but the run is still logged with the reason.

### Training in CI (MLO-01)

`.github/workflows/ml-train.yml` runs the commands a reviewer would run by hand, on every change to the
training code or the data generator (and on manual dispatch):

```
python -m shp_ml.synth --out data/noshow.parquet --seed 42          # dataset + noshow.json (hash)
python -m shp_ml.pipeline --data data/noshow.parquet --registry runs/registry --seed 42   # exit code 2 = gate failed
```

The job fails when a data check or a gate fails. The model (`model.txt`), `meta.json`, the model card and the run log
are published as the workflow artifact `no-show-model-<git sha>`; the dataset hash, the model hash and the gate table are
in the job summary. On `main`, when the repository variable `MLFLOW_TRACKING_URI` is set, the `register` job runs the
same pipeline against the cluster MLflow and sets `candidate`; without the variable it is skipped with a note. What
happens after `candidate` (shadow, canary, rollback) is [16-mlops §6](../../docs/03-architecture/detailed-design/16-mlops.md).

### Training image (MLO-02)

`Dockerfile.train` builds the environment for training, so that the same image, dataset hash and seed give the same model.
The base image is pinned by digest, the Python dependencies by hash (`requirements-train.lock`, installed with
`--require-hashes`), and the image sets one thread, `PYTHONHASHSEED=0` and UTC. The serving `Dockerfile` is unchanged.

```
docker build -f Dockerfile.train -t shp-ml-train:local .
scripts/reproduce.sh shp-ml-train:local 42        # two fresh containers, no network; exit 1 unless the hashes are equal
# one run by hand (output goes to the mounted directory):
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD/out:/work" shp-ml-train:local shp_ml.synth --out data/noshow.parquet --seed 42
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD/out:/work" shp-ml-train:local shp_ml.pipeline --data data/noshow.parquet --registry runs/registry --seed 42
```

Regenerate the lock after changing `requirements-train.in` or the base image (inside the same base image, so the markers and
wheels match its Python):

```
docker run --rm -v "$PWD":/w -w /w mirror.gcr.io/library/python@sha256:<digest of the base image> sh -c \
  'pip install -q uv && uv pip compile --generate-hashes --no-header --annotation-style=line --universal -o requirements-train.lock requirements-train.in'
```

Hashes are only comparable inside one environment: the `train` job of `ml-train` installs from `pyproject.toml` on the
runner's Python and may produce a different parquet or model than the image, which is the reference. CI builds and scans
the image on every change (`svc-ml` job `container-train`); pushing to ACR and the cosign signature only happen on
`main` when `ACR_NAME` is set.

**Limitations:** the model only relearns self-written group rules; the numbers demonstrate the MLOps process, not real predictive ability.

`shp_platform` in `src/` is a copy of `libs/python`: do not edit it here; edit the source and run `python tools/pylib/sync.py`.

## Serving, monitoring and retraining (US-064, US-065, US-073, US-074)

```
scheduling ─E1/E2/E3/E5, clinical E7─► consumer (inbox, 1 transaction) ─► appointment_facts, patient_stats
                                          └─► score production (+ shadow candidate) ─► predictions + outbox E10
02:00 VN  monitor: 7-day PSI (features from predictions) + 14-day live AUC ─► metric ─► retrain_requests
Job       retrain: lock ─► Parquet snapshot 24 months (hash) ─► pipeline + gates ─► candidate alias (never production)
```

| Command | Task |
|---|---|
| `uvicorn shp_ml.main:app --port 8090` (needs `MONGODB_URI`, `RABBITMQ_URL`, `REGISTRY_DIR` or `MLFLOW_TRACKING_URI`) | service: consumer, outbox relay, model loading, daily monitoring, `/metrics` |
| `python -m shp_ml.monitor` | run monitoring once (CronJob) |
| `python -m shp_ml.retrain --datasets /data/datasets` | process retraining requests (CronJob `ml-retrain` in shp-gitops, `concurrencyPolicy: Forbid`; training image) |

### Canary and shadow metrics (ML-25)
Every series carries `model_version`, so the canary AnalysisTemplate (MLO-03) and the shadow step (16-mlops §6) can compare versions.

| Metric | Use |
|---|---|
| `shp_ml_predict_seconds{model_version}` (histogram) | p95 latency (≤ 100 ms); `_count` = successful predictions |
| `shp_ml_predict_errors_total{model_version}` | error rate = errors / (errors + `shp_ml_predict_seconds_count`) |
| `shp_ml_score{model_version}` (histogram) | score distribution per version |
| `shp_ml_high_risk_total{model_version,high_risk}` | highRisk rate of served predictions (±5 points vs the old version) |
| `shp_ml_shadow_agreement_total{model_version,agree}` | highRisk agreement of the candidate with production (≥ 90%) |
| `shp_ml_shadow_score_psi{model_version}` | score PSI of the candidate against production on the same appointments, last `SHADOW_PSI_WINDOW` (1,000) pairs, exported from `SHADOW_PSI_MIN_PAIRS` (200) pairs on; gate < 0.1. Removed when the candidate changes |
| `shp_ml_model_info{model_version,role}` | loaded production model |

`SERVING_MODEL_VERSION` (MLO-03) pins the served version in the pod template so Argo Rollouts can canary a model change; the pod is
not ready until that version is loaded. Empty (default) = follow the `production` alias.

`shp_ml_score_psi` (no version label) is the daily drift of production scores against registration time (ML-10), not a canary metric.

Integration tests use a real MongoDB replica set and RabbitMQ via testcontainers (needs Docker); if `SHP_TEST_MONGO_URI` is set
the local cluster is used instead. Without Docker those tests are skipped.
