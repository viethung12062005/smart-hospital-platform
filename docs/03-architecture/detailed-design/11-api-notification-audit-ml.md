# 11 — API notification, audit, ML

Conventions: [07-api-conventions.md](07-api-conventions.md).

## 1. Notification (`/api/v1/notifications`)

Most activity is **asynchronous via events**; the API only serves user preferences, devices, and operations.

| # | Method + path | Purpose | Permission |
|---|---|---|---|
| N1 | `PUT /api/v1/notifications/devices` | Register/refresh a device token | P, D, N (own) |
| N2 | `DELETE /api/v1/notifications/devices/{tokenId}` | Unregister | token owner |
| N3 | `GET /api/v1/notifications/preferences` | View notification preferences | P |
| N4 | `PUT /api/v1/notifications/preferences` | Change channels/language/quiet hours | P |
| N5 | `GET /api/v1/notifications/reminders?appointment={id}` | View the reminders for an appointment | N, A |
| N6 | `GET /api/v1/notifications/dead-letters` | List of permanently failed reminders | A |
| N7 | `POST /api/v1/notifications/dead-letters/{id}/retry` | Put back in the queue | A |
| N8 | `POST /internal/v1/otp/send` | Create and send an OTP code (Zalo first, then SMS, or email) | clinical, Keycloak |
| N9 | `POST /internal/v1/otp/verify` | Verify the OTP code | clinical, Keycloak |

### N8/N9 OTP (internal, called from clinical and from Keycloak's authenticator)
`N8` accepts `{ purpose: "register"|"claim-record"|"link-relative"|"change-phone", channelHint: "phone"|"email", destination, locale, context }`
and returns `{ challengeId, channel, expiresAt }`. `N9` accepts `{ challengeId, code }` and returns `{ ok }`.
- **Channel:** for phone numbers, try **Zalo** (verification message) first; if Zalo fails, the number does not use Zalo, or there is no delivery result after 30 seconds, send
  **SMS with the same code**. Email uses the email channel. Each channel is an adapter behind the `OtpChannel` interface; there are fakes for dev and test.
- **Code:** 6 secure random digits, only the **hash** (HMAC) is stored in the `otp_challenges` collection (TTL 5 minutes), single use; 5 wrong attempts locks it for 15 minutes
  (429 `otp-locked`). Each number receives at most 5 codes/hour, each IP at most 20 codes/hour (429 `otp-rate-limited`).
- **Message content:** per versioned template; the authorization message (`link-relative`) states clearly **who** (shortened name) is requesting **which scope**. It does not include medical data.
- **Cost and monitoring:** metrics for messages by channel, delivery success rate, SMS fallback rate; alert when the monthly budget is exceeded.
- Only services with the `internal.otp` role can call it; the gateway does not route `/internal`. Never log `destination` or `code`.

### N1 `PUT /api/v1/notifications/devices`
`{ "platform": "ios|android|web", "token": "<FCM/APNs token>" }` → 200 `{ "tokenId" }`. Upsert by `token`; the owner comes from the
caller's `fhirUser` (not accepted from the body). 400 if the token is empty or > 4,096 characters.

### N4 `PUT /api/v1/notifications/preferences`
```json
{ "channels": ["push", "email", "sms"], "locale": "vi", "quietHours": { "start": "22:00", "end": "07:00" } }
```
`channels` ≥ 1 element, order = priority (later elements are fallback). The `t-2h` reminder **ignores** quiet hours. 400 if a channel is outside {push, sms, email}.

### N6/N7 Dead-letter
Item: `{ "reminderId", "kind", "subject": "Appointment/...", "attempts": 5, "lastError": "provider-timeout", "deadLetteredAt" }`
— contains **no** contact information. `retry` sets `status=scheduled`, `attempts=0`; 409 if the appointment is past its start time.

Notification content (multilingual) comes from versioned templates in the repo `templates/<kind>.<locale>.txt`, with variables:
`{doctorName}`, `{startTime}`, `{place}`; diagnosis or results are not included in the content (only "a new result is available, open the app to view").

## 2. Audit (`/api/v1/audit`)

Permissions: **DPO** (read, export), **A** (read, **does not** see the break-glass `reason` field and does not query by patient when
there is no valid request — operational note). There is no write/edit/delete endpoint (AC-09.2); writing happens only through events.

| # | Method + path | Purpose |
|---|---|---|
| A1 | `GET /api/v1/audit/events` | Search |
| A2 | `POST /api/v1/audit/exports` | Create an export report |
| A3 | `GET /api/v1/audit/exports/{id}` | Status / download link (5-minute SAS) |
| A4 | `GET /api/v1/audit/integrity` | Result of the most recent hash chain check |
| A5 | `GET /api/v1/audit/break-glass` | List of break-glass sessions awaiting DPO review |

### A1 `GET /api/v1/audit/events`
| Parameter | Note |
|---|---|
| `patientId` | optional |
| `actor` | `Practitioner/...` or subject |
| `action`, `outcome`, `purposeOfUse` | enum (multiple values comma-separated) |
| `from`, `to` | **required**, range ≤ 90 days |
| `limit` ≤ 200, `cursor` | |

Response `{ "items": [ <record per 05 §1.1> ], "nextCursor" }`. The query is performed on Log Analytics (KQL) or Loki; the
lookup **is also audited** (`action = audit-query`). Data latency ≤ 1 minute (NFR-SEC5).

### A2 `POST /api/v1/audit/exports`
`{ "from": "...", "to": "...", "patientId"?: "...", "format": "csv" }` → 202 `{ "exportId" }`. A background job writes the file to the
`audit-exports` container (retention 7 days) and writes an `export` audit record. At most 3 concurrent exports/user; range ≤ 366 days.

### A5 `GET /api/v1/audit/break-glass`
Status `new` → `acknowledged` (`PUT .../{id}` with `{ "decision": "justified|unjustified", "note" }`, DPO only). Each new
break-glass sends a notification to the DPO within ≤ 5 minutes (AC-10.1) via notification (email + push channels).

## 3. ML (`/api/v1/ml` + internal)

ML works mainly through events (see [04](04-db-notification-ml.md) §2.4). The public API is for observation only:

| # | Method + path | Purpose | Permission |
|---|---|---|---|
| M1 | `GET /api/v1/ml/predictions?appointment={id}` | Stored prediction for an appointment | N, A |
| M2 | `GET /api/v1/ml/model` | Running model version, metrics at registration | A |
| M3 | `POST /internal/v1/ml/predict` | Synchronous prediction (for testing/eval only) | internal services, CI |

### M1 response
```json
{ "appointmentId": "0192...", "score": 0.71, "threshold": 0.60, "highRisk": true,
  "modelName": "shp-no-show", "modelVersion": "7", "predictedAt": "2026-11-20T02:15:03Z",
  "features": { "lead_time_days": 21, "prior_noshow": 2, "prior_booked": 4, "start_hour": 8, "specialty": "cardiology" } }
```
(AC-15.2: every prediction stores the model version and features.) 404 if there is no prediction yet (ML off/failing — does not affect reminders, AC-15.1).

### M3 `POST /internal/v1/ml/predict`
Body: feature vector (per [04](04-db-notification-ml.md) §2.3) → `{ "score", "highRisk", "modelVersion" }`; p95 ≤ 100 ms.
The model is loaded from the MLflow registry by the `production` alias; changing the alias (after passing the quality gate AUC ≥ 0.75 and better than baseline,
NFR-AI6) → the pod reloads within ≤ 60 seconds without redeploying.

The `threshold` is configuration (default 0.60), chosen on the validation set to achieve precision ≥ 0.5 at maximum recall; it is stored with the model version.
