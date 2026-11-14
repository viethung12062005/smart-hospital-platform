# Phase 2 - Non-functional requirements (NFR)

> Status: **Approved - G2 passed on 2026-10-26**. Every NFR has a **number**, a **measurement method** and a **measurement environment**.
> The numbers are calculated from [workload-analysis.md](workload-analysis.md).

## 1. Performance

| ID | Requirement | Target | Measurement method |
|---|---|---|---|
| NFR-P1 | Find free slots | p95 <= 300 ms at 500 RPS | k6 on UAT (prod-sized), 10 minutes |
| NFR-P2 | Book an appointment | p95 <= 800 ms at 50 RPS, 5xx error rate < 0.1% | k6 on UAT (prod-sized) |
| NFR-P3 | Open a patient record / timeline | p95 <= 500 ms | k6 + trace |
| NFR-P4 | Issue document upload/download links | p95 <= 300 ms | k6 |
| NFR-P5 | RAG assistant | first token <= 3 s, full answer <= 10 s (p95) | eval harness + trace |
| NFR-P6 | Record summary | <= 15 s (p95) | eval harness |
| NFR-P7 | Document extraction | <= 2 minutes from upload (p95) | queue latency metric |
| NFR-P8 | Send appointment reminders | >= 99% sent within 5 minutes after the scheduled send time | notification metric |

## 2. Scalability

| ID | Requirement | Target |
|---|---|---|
| NFR-S1 | Withstand the 07:00 traffic surge | Auto-scale from the minimum level to sufficient capacity within <= 2 minutes, with no manual intervention |
| NFR-S2 | 5x load | p95 does not exceed 2x the NFR-P1/P2 target |
| NFR-S3 | 10x load | The system degrades gracefully (waiting room, 429) instead of crashing |
| NFR-S4 | Independent scaling | AI and documents scale separately and do not affect booking |

## 3. Availability and recovery

| ID | Requirement | Target |
|---|---|---|
| NFR-A1 | Availability of the booking and records API (SLO) | **99.9%**/month (<= 43 minutes of downtime) |
| NFR-A2 | Availability of AI features | 99.0%/month; AI failures do not break the clinical workflow (graceful degradation) |
| NFR-A3 | Withstand a single zone failure | No loss of committed data; services recover within <= 5 minutes |
| NFR-A4 | RPO / RTO for clinical and appointment data | **RPO <= 5 minutes, RTO <= 1 hour** |
| NFR-A5 | RPO for documents and audit | RPO = 0 for data whose write is confirmed (zone-redundant storage) |
| NFR-A6 | Dependency failures (SMS provider, LLM, ML) | No data loss; retry + dead-letter; fallback available (see UC-07, UC-11) |
| NFR-A7 | Zero-downtime deploy | Rolling/canary, 0 requests failed due to deploy |

## 4. Security and privacy

| ID | Requirement | Target |
|---|---|---|
| NFR-SEC1 | Authentication | OIDC; staff must use MFA; access token <= 15 minutes |
| NFR-SEC2 | Authorization | Enforce 100% of the [access control matrix](access-control-matrix.md), verified by automated tests in CI |
| NFR-SEC3 | Encryption | TLS 1.2+ on every connection (including within the cluster); encryption at rest for every data store |
| NFR-SEC4 | Secrets | No secrets in code/config/images; use Key Vault + workload identity; rotate without redeploying |
| NFR-SEC5 | Audit | 100% of patient data access is audited; appears in the search tool within <= 1 minute; immutable |
| NFR-SEC6 | Supply chain | Images are vulnerability-scanned (block High/Critical), signed and have an SBOM; the cluster runs only signed images |
| NFR-SEC7 | Network isolation | Deny all traffic between pods by default; open only designed flows; the DB has no public endpoint |
| NFR-SEC8 | AI | Redact PII before sending to external Azure models; resist prompt injection per the test suite; AI uses only the caller's permissions |
| NFR-SEC9 | Data | Synthetic data only in every environment; a synthetic data generator is provided |

## 5. Observability and operations

| ID | Requirement | Target |
|---|---|---|
| NFR-O1 | Distributed tracing | 100% of requests have a cross-service trace (OpenTelemetry) |
| NFR-O2 | SLO dashboard | SLI/SLO dashboard + error budget for NFR-A1, P1, P2 |
| NFR-O3 | Alerting | Alerts on fast error-budget burn rate, messages in the DLQ, certificates nearing expiry |
| NFR-O4 | Logs | Structured logs with a trace-id, **containing no PII** |

## 6. Deployability (DevOps)

| ID | Requirement | Target |
|---|---|---|
| NFR-D1 | Lead time | Commit -> signed image <= 15 minutes; -> running on UAT <= 30 minutes (after approval) |
| NFR-D2 | Rollback | <= 5 minutes, in a single operation (GitOps revert) |
| NFR-D3 | Infrastructure as code | 100% of infrastructure created with IaC; a new environment can be rebuilt from scratch |
| NFR-D4 | Environment isolation | `prod` has separate clusters and subscription/resource group from `uat`; dev runs locally |

## 7. Cost (FinOps)

| ID | Requirement | Target (pending team agreement based on the existing Azure infrastructure) |
|---|---|---|
| NFR-C1 | Infrastructure budget | No always-on cluster. Always-on resources (state, ACR) <= 10 USD/month; each hour a full environment runs <= 3 USD (dev) / <= 6 USD (prod); the total project budget is set by the PM |
| NFR-C2 | AI cost | Average <= 0.005 USD per RAG request (DeepSeek); Gemini only within the free tier; daily token quotas and alerts |
| NFR-C3 | Transparency | Cost tags by environment/service; weekly cost reports |

## 8. AI / ML quality

| ID | Requirement | Target |
|---|---|---|
| NFR-AI1 | RAG retrieval | Recall@5 >= 0.8; MRR >= 0.6 on an eval set of >= 100 questions |
| NFR-AI2 | RAG answers | Groundedness >= 0.9; citation accuracy >= 0.9; correct-refusal rate when no source exists >= 0.9 |
| NFR-AI3 | Prompt injection | 0 bypasses on an attack test set of >= 50 samples |
| NFR-AI4 | Extraction | Field-level F1 >= 0.85 on a labeled synthetic document set |
| NFR-AI5 | Booking chatbot | Completion rate >= 85%; 0 unconfirmed actions |
| NFR-AI6 | No-show model | AUC >= 0.75 and above baseline; drift monitored weekly |
| NFR-AI7 | Fallback | Every model in the fallback chain is evaluated; quality is reported per model |

## 9. Compatibility

| ID | Requirement | Target |
|---|---|---|
| NFR-I1 | Data standards | FHIR R4 APIs for the in-scope resources |
| NFR-I2 | Cross-platform clients | Web PWA usable on desktop and mobile (Chrome, Safari, the 2 most recent versions) |
| NFR-I3 | Cross-platform infrastructure | Runs on any Kubernetes (AKS is the primary target) and locally; no hard dependency on proprietary services when an equivalent exists |
