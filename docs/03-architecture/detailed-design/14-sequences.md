# 14 — Sequence diagrams for main flows

Each diagram marks the **synchronous** points (solid arrows), **asynchronous** points (dashed arrows), and failure points. API codes (S11, C14, etc.) reference
documents 08–12; event codes (E1, etc.) reference [13](13-events.md).

## 1. Booking (UC-03) — S11, E1

```mermaid
sequenceDiagram
    autonumber
    actor P as Patient (web)
    participant GW as Gateway
    participant SCH as Scheduling
    participant PG as PostgreSQL
    participant R as Redis
    participant BUS as Bus
    participant NTF as Notification
    participant ML as ML
    participant CLN as Clinical

    P->>GW: GET /fhir/Slot?specialty=cardiology&start=ge...
    GW->>SCH: (valid JWT, rate limit)
    SCH->>R: GET cache query key
    alt cache miss
        SCH->>PG: SELECT free slots (ix_slot_free_by_specialty)
        SCH->>R: SET TTL 5s
    end
    SCH-->>P: Bundle of free slots

    P->>GW: POST /fhir/Appointment/$book (Idempotency-Key)
    GW->>SCH: forward
    SCH->>CLN: identity(sub) → fhirUser (cache 60s)
    SCH->>PG: BEGIN, acquire idempotency key, patient advisory lock
    SCH->>PG: count future appointments (BR-03), UPDATE slot free→busy (conditional)
    alt slot already booked
        SCH->>PG: ROLLBACK
        SCH-->>P: 409 slot-unavailable + 3 suggested slots
    else success
        SCH->>PG: INSERT appointment, history, outbox(E1), outbox(E11), COMMIT
        SCH-->>P: 201 Appointment (booked)
        SCH->>R: clear slot cache for the doctor/day
    end

    Note over SCH,BUS: Relay (every 1s) adds ageBand then emits E1
    SCH--)BUS: E1 appointment-booked
    BUS--)NTF: E1 → create 3 reminders (confirmation, T-24h, T-2h)
    BUS--)ML: E1 → no-show scoring
    BUS--)CLN: E1 → care_relationships
    ML--)BUS: E10 no-show-scored
    BUS--)NTF: E10 → add T-6h reminder if high risk
```
**Failure points:** Redis down → skip the cache, read directly from the DB (slower, correct). Clinical slow → identity uses the cache; cache empty and clinical
down → 503 (do not book when the user cannot be identified). Bus down → E1 stays in the outbox and is published later; the appointment is still booked successfully.

## 2. Reminders and provider failure handling (UC-07)

```mermaid
sequenceDiagram
    participant W as Notification worker
    participant DB as PostgreSQL (notification)
    participant CLN as Clinical
    participant PUSH as Push provider
    participant SMS as SMS provider
    participant OBS as Alerting

    loop every 10 seconds
        W->>DB: fetch due reminders (FOR UPDATE SKIP LOCKED) → sending
    end
    W->>CLN: GET /internal/v1/patients/{id}/contact (audit)
    W->>PUSH: send (priority channel 1)
    alt push succeeds
        W->>DB: delivery_attempt accepted, reminder = sent
    else transient push error
        W->>SMS: send (fallback channel)
        alt SMS succeeds
            W->>DB: attempt accepted (channel=sms), reminder = sent
        else all channels fail
            W->>DB: reminder = scheduled, due_at = now + backoff (1, 5, 15, 60 minutes)
        end
    end
    Note over W,DB: 5th failure → dead-lettered
    W->>OBS: metric + DLQ alert
    Note over W,DB: Worker dies mid-way: locked_until expires → the row is reclaimed and sent again (idempotent thanks to the unique attempt)
```

## 3. Check-in → visit → finish (UC-04) — S16, C11, C14, E4–E6

```mermaid
sequenceDiagram
    actor N as Nurse
    actor D as Doctor
    participant SCH as Scheduling
    participant BUS as Bus
    participant CLN as Clinical
    participant ML as ML

    N->>SCH: POST /Appointment/{id}/$check-in (If-Match)
    SCH->>SCH: booked → arrived (window -60'/+30')
    SCH--)BUS: E4 checked-in
    BUS--)CLN: E4 → create Encounter(arrived), idempotent by appointmentId
    N->>CLN: POST /Observation (vital signs, attached to Encounter)
    D->>CLN: POST /Encounter/{id}/$start (checks Care)
    D->>CLN: POST /Condition (ICD-10), PUT /Encounter (history)
    D->>CLN: POST /Encounter/{id}/$finish (requires ≥1 diagnosis)
    CLN--)BUS: E6 encounter-finished
    BUS--)SCH: E6 → Appointment arrived → fulfilled
    SCH--)BUS: E5 status-changed(fulfilled)
    BUS--)ML: E5 → outcome=fulfilled, update attendance_stats
```

## 4. Document upload and AI extraction (UC-05, UC-13) — D1, D2, E8, AI6

```mermaid
sequenceDiagram
    actor N as Nurse
    participant DOC as Document
    participant BLOB as Blob Storage
    participant AV as Malware scanner
    participant BUS as Bus
    participant AI as AI service
    participant CLN as Clinical
    actor D as Doctor

    N->>DOC: POST /documents/uploads (check type, size, Care)
    DOC-->>N: SAS write 10 minutes
    N->>BLOB: PUT file (direct)
    N->>DOC: POST /uploads/{id}/complete
    DOC->>BLOB: check size + SHA-256 + magic bytes
    DOC-->>N: 202 scanning
    BLOB->>AV: scan when blob is created
    AV-->>DOC: result
    alt malware
        DOC->>BLOB: move to quarantine, alert, do NOT emit E8
    else clean
        DOC--)BUS: E8 document-uploaded
        BUS--)AI: E8 → extraction_job (queued)
        AI->>DOC: POST /internal/documents/{id}/read-url (60s)
        AI->>BLOB: read file → OCR → classify → extract fields → validate
        Note over AI: Any failing step → retry (1/5/15') → failed (DLQ). The upload is not affected
        AI->>AI: status = needs-review
    end
    D->>AI: GET /extractions (queue) → view fields + original document
    D->>AI: POST /extractions/{id}/review (accepted, may edit)
    AI->>CLN: POST /Observation (with the DOCTOR's TOKEN), derivedFrom=DocumentReference
```

## 5. Knowledge assistant with fallback (UC-11) — AI1

```mermaid
sequenceDiagram
    actor U as Doctor
    participant AI as AI service
    participant SRCH as AI Search
    participant M1 as DeepSeek (primary)
    participant M2 as Gemini free tier (fallback 1)
    participant M3 as Ollama (fallback 2)
    participant AUD as Audit

    U->>AI: POST /assistant/ask
    AI->>AI: validate input (length, injection patterns)
    AI->>SRCH: hybrid (local embedding), filter role/department (security trimming)
    alt no chunk above the threshold
        AI-->>U: answered=false (does not call the LLM)
    else context available
        AI->>M1: prompt with PII redacted (context marked <document>)
        alt M1 timeout / 5xx / 429 / circuit open
            AI->>M2: try the next model (PII redacted)
            alt M2 fails
                AI->>M3: try the next model
            end
        end
        alt answer available
            AI->>AI: validate schema + citations match chunks + no diagnosis
            AI-->>U: answer + citations (+ model, fallbackLevel)
        else all models fail
            AI-->>U: mode=retrieval-only (list of related sections)
        end
    end
    AI--)AUD: E11 ai-query (model, tokens, cost, no question content)
```

## 6. Emergency access — break-glass (UC-10) — C20

```mermaid
sequenceDiagram
    actor D as Doctor (no Care)
    participant CLN as Clinical
    participant BUS as Bus
    participant AUD as Audit
    participant NTF as Notification
    actor DPO

    D->>CLN: GET /fhir/Patient/{id}
    CLN-->>D: 403 break-glass-required (+ audit denied)
    D->>CLN: POST /break-glass {patientId, reason ≥ 20 characters}
    CLN->>CLN: create a 60-minute session (TTL)
    CLN--)BUS: E11 break-glass-start (ETREAT)
    CLN-->>D: 201 {expiresAt}
    BUS--)AUD: write WORM + Log Analytics
    AUD--)NTF: alert DPO (≤ 5 minutes)
    NTF--)DPO: email + push
    D->>CLN: GET /fhir/Patient/{id}, /timeline (audit ETREAT each time)
    DPO->>AUD: GET /audit/break-glass → review (justified / unjustified)
```
