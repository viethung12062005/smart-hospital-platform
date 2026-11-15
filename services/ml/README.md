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
| `python -m shp_ml.retrain --datasets /data/datasets` | process retraining requests (Kubernetes Job, training image) |

Integration tests use a real MongoDB replica set and RabbitMQ via testcontainers (needs Docker); if `SHP_TEST_MONGO_URI` is set
the local cluster is used instead. Without Docker those tests are skipped.
