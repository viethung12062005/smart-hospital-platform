# 05 — Audit and Blob storage

## 1. Audit records

Every service emits the `shp.audit.recorded.v1` event (via outbox) for **every** read/write of patient data, every issuance of
a document link, every AI query on patient data, and **every denial (403)**. The audit service is the only consumer.

### 1.1 Record schema (contains no clinical content)

| Field | Type | Example / note |
|---|---|---|
| `eventId` | uuid | = CloudEvents id (used for deduplication) |
| `occurredAt` | datetime UTC | |
| `service` | string | `clinical` |
| `action` | enum | `read` · `search` · `create` · `update` · `delete` · `download-url` · `ai-query` · `ai-summary` · `break-glass-start` · `export` · `login` |
| `outcome` | enum | `success` · `denied` · `error` |
| `actor.subject` | string | OIDC subject |
| `actor.role` | enum | `patient` · `doctor` · `nurse` · `admin` · `dpo` · `system` |
| `actor.fhirUser` | string | `Practitioner/0192...` |
| `onBehalfOf` | string \| null | `Patient/0192...` of the managed person when an account acts through `profile_links` (with `relationship`); null if there is no delegation |
| `patientId` | uuid \| null | the patient being accessed (null if not patient-related) |
| `resource` | string | `Encounter/0192...` or `Patient?name=...` (for search, record only the parameter name, **not the value**) |
| `purposeOfUse` | enum | `TREAT` (treatment) · `ETREAT` (emergency, break-glass) · `HOPERAT` (operations) · `PATRQT` (patient self-view) |
| `reason` | string \| null | break-glass reason or denial reason (`no-care-relationship`) |
| `traceId` | string | W3C trace id, linked to the trace in observability |
| `client.ip` | string | IP with the last octet masked |
| `client.userAgent` | string | shortened |

Can be exported to FHIR `AuditEvent` when exchange is needed (1-1 mapping of the fields above).

### 1.2 Immutable original (Blob WORM)

- Storage account `stshpaudit<suffix>`, container `audit`, **time-based immutability policy** (prod: locked, 10 years per
  assumption BR-11; non-prod: unlocked, 1 day so the environment can be destroyed).
- The audit service batches records every **60 seconds or 5,000 records**, writing an **append blob** in NDJSON:
  `audit/v1/yyyy/MM/dd/HH/<instance>-<sequence>.ndjson`.
- **Hash chain** against silent modification/deletion: the first line of each file is the header
  `{"type":"batch-header","prevBatch":"<path>","prevHash":"<sha256>","count":N}`; the batch hash is
  `SHA-256(prevHash + content of the lines)`, stored in blob metadata `batchHash` and used as `prevHash` for the next batch
  (per instance). The `audit-verify` tool (AUD-06) walks the whole chain and reports breaks.
- Only the audit service has `Storage Blob Data Contributor` on the container; nobody has delete permission during the lock period (including the Owner).

### 1.3 Query table (Log Analytics)

Custom table `SHPAudit_CL`, written through the Logs Ingestion API (Data Collection Rule). Target latency ≤ 1 minute (NFR-SEC5).
Local: Loki with the same labels.

| Column | Type |
|---|---|
| `TimeGenerated` | datetime |
| `EventId`, `Service`, `Action`, `Outcome`, `ActorSubject`, `ActorRole`, `ActorFhirUser`, `PatientId`, `Resource`, `PurposeOfUse`, `Reason`, `TraceId`, `ClientIp` | string |

Retention: 90 days interactive + 2 years archive; the long-term original is in Blob WORM.

Sample queries for the DPO (UC-09):

```kusto
// Who viewed patient X's record in the past 30 days?
SHPAudit_CL
| where TimeGenerated > ago(30d) and PatientId == "0192f8a4-..."
| summarize Views = count(), LastSeen = max(TimeGenerated) by ActorFhirUser, ActorRole, Action, PurposeOfUse
| order by LastSeen desc

// All break-glass events in the week
SHPAudit_CL | where TimeGenerated > ago(7d) and Action == "break-glass-start"

// Permission probe: actors denied many times within 1 hour
SHPAudit_CL | where Outcome == "denied" | summarize n = count() by ActorSubject, bin(TimeGenerated, 1h) | where n > 20
```

## 2. Blob for clinical documents

| Storage account | Container | Path | Note |
|---|---|---|---|
| `stshpdocs<suffix>` | `documents` | `patients/{patientId}/{documentId}/v{version}/original.{pdf\|jpg\|png}` | Shared key disabled, managed identity only (Entra); private endpoint; versioning + soft delete 14 days |
| | `quarantine` | as above | Files with malware are moved here; only the security admin can read them |
| | `kb` | `kb/{kbDocumentId}/v{version}/{filename}` | Original documents of the RAG knowledge base (ai service) |
| `stshpaudit<suffix>` | `audit` | see §1.2 | WORM |

- **Upload**: the client receives a **user delegation SAS** with only `create/write` permission for exactly one blob, valid for 10 minutes, restricted
  by `Content-Type`; the client uploads directly to Blob (not through the API, so it does not consume service bandwidth).
- **Download**: a user delegation SAS with only `read` permission for exactly one blob, **valid for 5 minutes** (BR-08), with
  `Content-Disposition: attachment`; every issuance writes an audit `download-url` record.
- **Malware scan**: Microsoft Defender for Storage (cloud) / ClamAV (local) scans when the blob is created; results are pushed back to the document
  service through Event Grid → queue (local: simulated webhook).
- **Lifecycle**: `documents` moves to Cool after 30 days and Cold after 180 days; prod uses GZRS, non-prod uses ZRS.
