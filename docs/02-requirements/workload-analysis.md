# Phase 2 - Workload analysis

> Calculated from the assumptions in [business-analysis.md](../01-business-analysis/business-analysis.md) Section 1.
> These figures are inputs for the NFRs, infrastructure sizing and load test scenarios.

## 1. Users

| Group | Total | Concurrent activity at peak |
|---|---|---|
| Patients | 50,000 registered (year 1) | 500 normal; **5,000** when slots open (07:00 daily) |
| Doctors | 120 | 120 |
| Nurses | 250 | 150 |
| Admin / DPO | ~10 | 5 |

## 2. Transactions by use case

| Flow | Volume / day | Peak | Calculation notes |
|---|---|---|---|
| Find free slots (UC-03) | ~20,000 | **500 RPS** for 5 minutes after 07:00 | 900 bookings x ~20 searches; slot-opening surge of 5,000 people, each ~6 requests/minute |
| Book an appointment (UC-03) | ~900 (60% of 1,500 visits) | **50 RPS** for 2 minutes | High contention: many people target the same "good" slots |
| Read a record / timeline (UC-06) | ~48,000 | 15 RPS | 120 doctors x 40 patients x 10 reads |
| Write encounter, vital signs (UC-04) | ~6,000 | 3 RPS | 1,500 visits x ~4 writes |
| Upload documents (UC-05) | ~900 files | 1 RPS | 1,500 x 30% with paperwork x 2 files |
| Appointment reminders (UC-07) | ~2,000 messages | 200 messages/minute | 2 reminders / appointment + an extra reminder for high-risk cases |
| Audit events (UC-09) | ~250,000 | 100 events/second | Each read/write of patient data generates 1 event |
| Ask the RAG assistant (UC-11) | ~1,200 | 2 RPS | 120 doctors x 10 questions |
| Record summary (UC-12) | ~1,500 | 1 RPS | 1 per visit |
| Document extraction (UC-13) | ~900 | queue-driven | No realtime requirement |
| Booking chatbot (UC-14) | ~300 conversations | 1 RPS | |
| No-show prediction (UC-15) | ~900 | 50 RPS (at the same time as booking) | |

**Load characteristics:** reads outnumber writes (~20:1 in scheduling); there is a **predictable traffic surge** at 07:00;
write contention is concentrated on a few slots; AI has high latency and token-based cost; audit writes are steady and append-only.

## 3. Data volume

| Data | Size | Year 1 | Growth |
|---|---|---|---|
| Patient | ~3 KB x 50,000 | ~150 MB | +30%/year |
| Appointment + Slot | ~2 KB x (330k appointments + 1.2 million slots) | ~3 GB (including history and indexes) | linear |
| Encounter / Observation / Condition | ~4 KB x ~2.2 million records | ~9 GB | linear |
| Documents (files) | ~1.5 MB x 330k | **~500 GB** | linear; move to the Cool tier after 30 days |
| Audit | ~1 KB x 90 million | ~90 GB | retained long-term, immutable |
| RAG knowledge base | ~2,000 documents, ~100k chunks | ~2 GB (including vectors) | slow |

## 4. Load increase scenarios (for the "what about 5x or 10x" question)

| Factor | Find slots | Book appointment | Expected bottleneck | Mitigation |
|---|---|---|---|---|
| 1x | 500 RPS | 50 RPS | - | Baseline |
| 5x | 2,500 RPS | 250 RPS | Scheduling DB (reads) | Cache free slots in Redis; HPA scales pods; read replica |
| 10x | 5,000 RPS | 500 RPS | Write contention, connection pool | Virtual queue (waiting room) when slots open; per-user rate limiting; PgBouncer |

The load test scenarios in Phase 6 follow exactly the figures above.
