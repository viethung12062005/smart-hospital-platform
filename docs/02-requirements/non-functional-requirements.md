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

| ID | Requirement | Target | Measurement method |
|---|---|---|---|
| NFR-S1 | Withstand the 07:00 traffic surge | Auto-scale from the minimum level to sufficient capacity within <= 2 minutes, with no manual intervention | k6 ramp 0 -> 500 RPS in 1 minute on UAT (prod-sized); KEDA cron + HPA; pass: scaled out within <= 2 minutes |
| NFR-S2 | 5x load | p95 does not exceed 2x the NFR-P1/P2 target | k6 on UAT (prod-sized), 10 minutes; pass: p95 <= 2x the P1/P2 target |
| NFR-S3 | 10x load | The system degrades gracefully (waiting room, 429) instead of crashing | k6 on UAT (prod-sized); no pod restarts due to OOM; pass: 429/queueing, no outage |
| NFR-S4 | Independent scaling | AI and documents scale separately and do not affect booking | k6 on UAT running AI and booking load in parallel; pass: P1/P2 stay within their targets |

## 3. Availability and recovery

| ID | Requirement | Target | Measurement method |
|---|---|---|---|
| NFR-A1 | Availability of the booking and records API (SLO) | **99.9%**/month (<= 43 minutes of downtime) | Grafana SLI/SLO + error budget dashboard; pass: error budget not exhausted |
| NFR-A2 | Availability of AI features | 99.0%/month; AI failures do not break the clinical workflow (graceful degradation) | Grafana SLI + failure test with the model disabled; pass: clinical flow still works |
| NFR-A3 | Withstand a single zone failure | No loss of committed data; services recover within <= 5 minutes | Failure drill on UAT: cordon every node in one zone; pass: no committed data lost, recovery <= 5 minutes |
| NFR-A4 | RPO / RTO for clinical and appointment data | **RPO <= 5 minutes, RTO <= 1 hour** | Restore drill with a written report; pass: restore completes within the RTO |
| NFR-A5 | RPO for documents and audit | RPO = 0 for data whose write is confirmed (zone-redundant storage) | Check zone-redundant storage after failover; pass: no confirmed write lost |
| NFR-A6 | Dependency failures (SMS provider, LLM, ML) | No data loss; retry + dead-letter; fallback available (see UC-07, UC-11) | Simulate SMS/LLM/ML failures on UAT; pass: no data loss, retry + dead-letter + fallback work |
| NFR-A7 | Zero-downtime deploy | Rolling/canary, 0 requests failed due to deploy | k6 running during a rolling/canary deployment on UAT; pass: 0 failed requests caused by the deploy |

## 4. Security and privacy

| ID | Requirement | Target | Measurement method |
|---|---|---|---|
| NFR-SEC1 | Authentication | OIDC; staff must use MFA; access token <= 15 minutes | pytest/JUnit integration test with the IdP; check the MFA claim; pass: no access without MFA, token lifetime <= 15 minutes |
| NFR-SEC2 | Authorization | Enforce 100% of the [access control matrix](access-control-matrix.md), verified by automated tests in CI | pytest/JUnit tests generated from the matrix (allow and deny per cell) + a test that every endpoint has a rule; pass: 100% correct, deny by default |
| NFR-SEC3 | Encryption | TLS 1.2+ on every connection (including within the cluster); encryption at rest for every data store | Scan the TLS configuration + check the Terraform configuration (Trivy config scan); pass: no insecure TLS or plaintext at rest |
| NFR-SEC4 | Secrets | No secrets in code/config/images; use Key Vault + workload identity; rotate without redeploying | gitleaks + push protection in CI; key rotation drill; pass: no secret found, rotation without redeploy |
| NFR-SEC5 | Audit | 100% of patient data access is audited; appears in the search tool within <= 1 minute; immutable | pytest/JUnit test correlating operations with audit entries; attempt to modify/delete the WORM store; pass: every access audited, searchable <= 1 minute, immutable |
| NFR-SEC6 | Supply chain | Images are vulnerability-scanned (block High/Critical), signed and have an SBOM; the cluster runs only signed images | Trivy + cosign + SBOM in the CI pipeline; Kyverno rejects unsigned images; pass: no High/Critical image, only signed images run |
| NFR-SEC7 | Network isolation | Deny all traffic between pods by default; open only designed flows; the DB has no public endpoint | Check NetworkPolicies; attempt unauthorized connections (pytest/JUnit); pass: only designed flows open |
| NFR-SEC8 | AI | Redact PII before sending to external Azure models; resist prompt injection per the test suite; AI uses only the caller's permissions | pytest tests that block requests containing PII; the >= 50 attack samples (NFR-AI3); permission tests; pass: 0 successful injections |
| NFR-SEC9 | Data | Synthetic data only in every environment; a synthetic data generator is provided | Synthetic data generator + review; pass: no real data in any environment |

## 5. Observability and operations

| ID | Requirement | Target | Measurement method |
|---|---|---|---|
| NFR-O1 | Distributed tracing | 100% of requests have a cross-service trace (OpenTelemetry) | Check a sample trace through gateway -> service -> bus (Jaeger/OTel); pass: trace-id present end to end |
| NFR-O2 | SLO dashboard | SLI/SLO dashboard + error budget for NFR-A1, P1, P2 | Grafana dashboard review; pass: dashboards exist for A1/P1/P2 |
| NFR-O3 | Alerting | Alerts on fast error-budget burn rate, messages in the DLQ, certificates nearing expiry | Trigger each alert condition (failure test); pass: each alert fires |
| NFR-O4 | Logs | Structured logs with a trace-id, **containing no PII** | Log scanning test (NFR-SEC13); pass: no PII found |

## 6. Deployability (DevOps)

| ID | Requirement | Target | Measurement method |
|---|---|---|---|
| NFR-D1 | Lead time | Commit -> signed image <= 15 minutes; -> running on UAT <= 30 minutes (after approval) | Pipeline statistics (CI); pass: median within the target |
| NFR-D2 | Rollback | <= 5 minutes, in a single operation (GitOps revert) | Rollback drill (GitOps revert); pass: <= 5 minutes |
| NFR-D3 | Infrastructure as code | 100% of infrastructure created with IaC; a new environment can be rebuilt from scratch | Create/destroy environments with a workflow (DR drill); pass: rebuildable from scratch |
| NFR-D4 | Environment isolation | `prod` has separate clusters and subscription/resource group from `uat`; dev runs locally | Configuration review; pass: separate clusters/subscriptions/resource groups |

## 7. Cost (FinOps)

> The per-hour environment ceilings and the always-on budget below are the SA's working assumption based on the existing Azure
> infrastructure; the **total project budget is decided by the PM**. Until the PM signs off, these figures are the working assumption.

| ID | Requirement | Target | Measurement method |
|---|---|---|---|
| NFR-C1 | Infrastructure budget | No always-on cluster. Always-on resources (state, ACR) <= 10 USD/month; each hour a full environment runs <= 3 USD (dev) / <= 6 USD (prod); the total project budget is set by the PM | Azure cost report by tag (`az` / cost-guard); pass: always-on <= 10 USD/month, environment-hour within the ceiling |
| NFR-C2 | AI cost | Average <= 0.005 USD per RAG request (DeepSeek); Gemini only within the free tier; daily token quotas and alerts | Token/cost metrics in Grafana; pass: average <= 0.005 USD per RAG request, quotas not exceeded |
| NFR-C3 | Transparency | Cost tags by environment/service; weekly cost reports | Weekly cost report review; pass: tags on every environment/service and the report is produced |

## 8. AI / ML quality

| ID | Requirement | Target | Measurement method |
|---|---|---|---|
| NFR-AI1 | RAG retrieval | Recall@5 >= 0.8; MRR >= 0.6 on an eval set of >= 100 questions | eval harness on >= 100 questions; pass: thresholds met |
| NFR-AI2 | RAG answers | Groundedness >= 0.9; citation accuracy >= 0.9; correct-refusal rate when no source exists >= 0.9 | eval harness; pass: thresholds met |
| NFR-AI3 | Prompt injection | 0 bypasses on an attack test set of >= 50 samples | eval harness automated attack set (>= 50 samples); pass: 0 bypasses |
| NFR-AI4 | Extraction | Field-level F1 >= 0.85 on a labeled synthetic document set | eval harness on a labeled synthetic document set; pass: F1 >= 0.85 |
| NFR-AI5 | Booking chatbot | Completion rate >= 85%; 0 unconfirmed actions | eval harness automated conversation scenarios; pass: >= 85% completion, 0 unconfirmed actions |
| NFR-AI6 | No-show model | AUC >= 0.75 and above baseline; drift monitored weekly | Offline evaluation + drift monitoring (Grafana); pass: AUC >= 0.75 and above baseline |
| NFR-AI7 | Fallback | Every model in the fallback chain is evaluated; quality is reported per model | Per-model report from the eval harness; pass: every model reported |

## 9. Compatibility

| ID | Requirement | Target | Measurement method |
|---|---|---|---|
| NFR-I1 | Data standards | FHIR R4 APIs for the in-scope resources | FHIR validator in contract tests; pass: resources validate |
| NFR-I2 | Cross-platform clients | Web PWA usable on desktop and mobile (Chrome, Safari, the 2 most recent versions) | Multi-browser E2E test (Playwright); pass: desktop + mobile, 2 most recent versions |
| NFR-I3 | Cross-platform infrastructure | Runs on any Kubernetes (AKS is the primary target) and locally; no hard dependency on proprietary services when an equivalent exists | Run the same chart on kind and AKS (CI); pass: works on both |
