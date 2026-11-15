# ADR-0007: No-show prediction model (MLOps)

## Status
Accepted (2026-10-26) — approved by the Solution Architect/PM together with gate G3.

## Context
We want a self-trained ML model to demonstrate the MLOps lifecycle, tied to real business.

## Decision
Predict the **probability that a patient will not attend** for each Appointment. `ml-service` listens to the
`appointment-booked` event, scores it, and **emits the `no-show-scored` event**; `notification-service` listens to this event to send additional
reminders for high-risk appointments. There is no synchronous call between the two services, so an ML failure does not affect reminders (AC-15.1).

- Data: **synthetic** with built-in patterns (lead time, past no-shows, appointment hour...).
- Model: Logistic Regression baseline → LightGBM (Python, retained per ADR-0015).
- Lifecycle: training pipeline (CI), experiment tracking + model registry with **MLflow**,
  quality gate (AUC, subgroup, calibration, latency), model card, shadow + canary automated with Argo Rollouts,
  drift monitoring (PSI) and actual AUC, automatic retraining on drift. Details: `detailed-design/16-mlops.md`.

## Alternatives considered
- Symptom urgency classification (triage): high medical risk, difficult to generate realistic synthetic data.
- Clinic load forecasting (time series): nice but less tied to the end-to-end flow.

## Consequences
Model results are only meaningful on synthetic data; the report must state this limitation.
- Adds ~60 person-hours (dev + SA) compared with a train/register-only version. In return, a closed MLOps lifecycle: versioned data → train → gate → canary → monitor → retrain.
- Automatic retraining has guardrails: 7-day cooldown, at most 2 times/14 days, if the model fails the gate production stays unchanged.
