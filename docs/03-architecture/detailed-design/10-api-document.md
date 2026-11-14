# 10 — API document

Conventions: [07-api-conventions.md](07-api-conventions.md). DB and Blob: [03](03-db-clinical-document.md) §3, [05](05-storage-audit-blob.md) §2.
Permissions: **P** Patient, **D** Doctor, **N** Nurse. Care/own as in [15-authorization.md](15-authorization.md).

## 1. Endpoint summary

| # | Method + path | Purpose | Permission | UC |
|---|---|---|---|---|
| D1 | `POST /api/v1/documents/uploads` | Start an upload, receive a SAS | D (Care), N | UC-05 |
| D2 | `POST /api/v1/documents/uploads/{uploadId}/complete` | Confirm the upload is finished | the upload creator | UC-05 |
| D3 | `GET /fhir/DocumentReference/{id}` | Read metadata | P (own + released), D (Care/BG), N | UC-05 |
| D4 | `GET /fhir/DocumentReference` | Find documents | as in D3 | UC-05, UC-06 |
| D5 | `POST /api/v1/documents/{id}/download-url` | Issue a 5-minute download link | as in D3 | UC-05 |
| D6 | `POST /api/v1/documents/{id}/release` | Release to the patient | D (Care) | UC-05 |
| D7 | `PUT /fhir/DocumentReference/{id}` | Mark as entered in error (`entered-in-error`) | D (Care, uploader) | UC-05 |
| D8 | `GET /internal/v1/documents?patient={id}` | List for the timeline | service `clinical` | UC-06 |
| D9 | `POST /internal/v1/documents/{id}/read-url` | Read link for OCR (60 seconds) | service `ai` | UC-13 |

## 2. Upload (two steps, the file goes directly to Blob)

### D1 `POST /api/v1/documents/uploads`
Header: `Idempotency-Key`. Body:
```json
{
  "patientId": "0192f8a4-...",
  "encounterId": "0192f8a4-...",
  "type": "11502-2",
  "title": "Blood test result 20/11/2026",
  "description": "Complete blood count, blood glucose",
  "fileName": "xet-nghiem-mau.pdf",
  "contentType": "application/pdf",
  "size": 482113,
  "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
}
```
Checks performed up front, **before issuing the SAS**:
| Condition | HTTP / `code` |
|---|---|
| `contentType` is not one of `application/pdf`, `image/jpeg`, `image/png` | 415 `invalid-file-type` (AC-05.1) |
| `size` > 20,971,520 bytes or ≤ 0 | 413 `file-too-large` (AC-05.1) |
| `type` is outside the value set; `fileName` contains path characters | 400 `validation-failed` |
| The caller has no Care with the patient (D) or `encounterId` does not belong to the patient | 403 `no-care-relationship` / 400 |
| 429 | more than 30 uploads/hour/user |

201:
```json
{
  "uploadId": "0192f8a4-...",
  "documentId": "0192f8a4-...",
  "upload": {
    "method": "PUT",
    "url": "https://stshpdocs....blob.core.windows.net/documents/patients/.../original.pdf?<user-delegation-sas>",
    "headers": { "x-ms-blob-type": "BlockBlob", "Content-Type": "application/pdf" },
    "expiresAt": "2026-11-20T02:25:00Z"
  }
}
```
The SAS grants only `create + write` on exactly one blob, valid for 10 minutes, with a `Content-Type` constraint; writes `upload_sessions` (TTL 15 minutes).
The Document is created with `docStatus = preliminary`, `processing.state = pending-upload`.

### D2 `POST /api/v1/documents/uploads/{uploadId}/complete`
The server checks that the blob actually exists, that **`size` and SHA-256 match** the declared values, and that the real `contentType` (read from magic bytes) matches.
| Result | |
|---|---|
| 202 | `processing.state = scanning`; returns `{ "documentId", "state": "scanning" }` |
| 422 `upload-incomplete` | No blob yet, or size/hash/type mismatch (the blob was deleted) |
| 409 | Session already `completed` or expired (`expired`) |

When the scanner returns a result: clean → `available`, `docStatus = final`, emits `shp.document.document-uploaded.v1`;
infected → move the blob to the `quarantine` container, `quarantined`, emit audit and an alert (does not emit `document-uploaded`).
The client polls with `GET /fhir/DocumentReference/{id}` (no `content` is returned until `available`).

## 3. Read and search

### D3 `GET /fhir/DocumentReference/{id}`
Returns metadata, **with no download URL**. `content[0].attachment` has `contentType`, `size`, `hash`, `title`. A Patient only sees it when
`released-to-patient` is set and it is their own document, otherwise 404. For a `quarantined`/`pending-upload` document, only the uploader and the Security Admin can see the status.

### D4 `GET /fhir/DocumentReference`
Parameters: `patient` (**required**), `encounter`, `type`, `date` (ge/le), `status`, `_count`, `_page_token`. Patient is forced to
`patient = self` and only released documents.

### D5 `POST /api/v1/documents/{id}/download-url`
Header: `Idempotency-Key` not needed (does not change state). Response:
```json
{ "url": "https://stshpdocs....blob.core.windows.net/documents/...?<sas>", "expiresAt": "2026-11-20T02:20:00Z", "fileName": "xet-nghiem-mau.pdf", "contentType": "application/pdf" }
```
- The SAS is **read-only for exactly one blob, valid for 5 minutes**, with `Content-Disposition: attachment` (BR-08, AC-05.2): after 5 minutes Blob returns 403.
- Only issued when `processing.state = available`; otherwise 409 `document-not-available`.
- **Every issuance** emits `audit-recorded` (`download-url`) — including denials.
- Limit 30 times/minute/user.

### D6 `POST /api/v1/documents/{id}/release`
Body `{ "note": "Normal result" }` optional. Sets `released-to-patient` (who, when), emits
`shp.document.document-released.v1` → notification sends "new result available" (UC-07). 409 if already released or not yet `available`.
Cannot be revoked after release (only `entered-in-error`).

## 4. Internal API

| Endpoint | Called by | Note |
|---|---|---|
| D8 `GET /internal/v1/documents?patient={id}&limit=` | clinical | shortened metadata to build the timeline |
| D9 `POST /internal/v1/documents/{id}/read-url` | ai | 60-second read SAS for exactly the extraction job's blob; audit actor `system` |

## 5. Error handling and cleanup

| Situation | Behavior |
|---|---|
| Client finishes uploading but does not call `complete` | The blob is cleaned up after `upload_sessions` expires (hourly job deletes orphan blobs + `pending-upload` > 24 hours) |
| Scanner error/timeout | Retry 3 times; still failing → keep `scanning`, operational alert (OBS-03); the document is **not** given a download link |
| Upload with the same `Idempotency-Key` twice | Return the same `documentId`/SAS if not expired |
