# Vietnamese synthetic data specification (ARC-11)

Purpose: everyone (testing, demo, load test) uses the same way of generating data that looks realistic but is **never real data**. The generator is built by the SA (DAT-01, DAT-02); this document is the input specification.

## 1. Principles
1. All data is synthetic. Never sample, mask or "shuffle" real people's data.
2. **Reproducible:** the generator takes `seed` (integer) and `scale`; the same seed + scale produce byte-for-byte identical results.
3. Identifiers are marked as synthetic so they cannot be confused with real ones (section 3).
4. Referential integrity: appointments point to existing patients/doctors/slots; encounters point to `fulfilled` appointments.
5. Distributions are realistic but not derived from real data (section 5).

## 2. Entities and counts by scale
| Entity (FHIR) | `small` (test, local) | `medium` (UAT) | `large` (prod-sized UAT, load test) |
|---|---|---|---|
| Patient | 200 | 20 000 | 500 000 |
| Practitioner | 12 | 120 | 600 |
| Schedule/Slot | 30 days x doctor | 90 days | 365 days |
| Appointment | 600 | 100 000 | 2 000 000 |
| Encounter + Observation | per the `fulfilled` schedule | same | same |
| DocumentReference | 100 (sample files of a few KB) | 5 000 | 50 000 |
Specialties, cancellation reasons, document types, LOINC and ICD-10 come from `contracts/terminology` (do not invent codes).

## 3. Per-field rules
| Field | Rule | "Synthetic" marker |
|---|---|---|
| Full name | Common surnames (Nguyen, Tran, Le, Pham, Hoang, Vu, Dang, Bui...) + middle name + given name by sex; full Vietnamese diacritics (NFC) | Append the suffix `(test)` to `Patient.name.text` when `scale` = `small` |
| Sex, date of birth | Sex ratio 49/51; ages per the distribution in section 5; valid dates of birth | - |
| Phone number | Format `0xxxxxxxxx` from the **unallocated** range `09000xxxxx` (100,000 numbers, enough for `small`/`medium`); `large` (500,000 patients) extends to `0900xxxxxx`, with the first 100,000 numbers overlapping the original range. One number per patient, unique (the number is used for duplicate-record checks, 01 section 4.1) | Range reserved for testing |
| Email | `<slug>@example.test` | `.test` domain (RFC 2606) |
| National ID | 12 digits starting with `000`; invalid under real province codes | `000` prefix |
| Health insurance number | `TEST` + 11 digits | `TEST` prefix |
| Address | Real province/district/ward, random house number and street name | House number `0` or `(synthetic)` in `address.text` |
| Internal ID | UUIDv7 generated from the seed | - |
| Doctor | Title + name; specialty from the catalog; working hours 07:00-17:00, lunch break 12:00-13:00 | `.test` email |

## 4. Clinical data
- Diagnoses come from the ICD-10 catalog in `contracts/terminology`; vital signs per LOINC (pulse, blood pressure, temperature, SpO2...), values within physiological ranges, with a small proportion abnormal (approx. 8%) to test alerts.
- Free-text clinical notes are **generated from sentence templates** (no external source); they contain no real person names or real healthcare facilities.
- Document files: empty PDFs/images with "SYNTHETIC DATA" printed on them; some "infected" files use the EICAR string to test ClamAV.

## 5. Distributions
- Patient age: 0-14 (18%), 15-44 (35%), 45-64 (29%), >= 65 (18%).
- Appointments: peaks 07:00-09:00 and 13:30-15:00; Monday is the busiest; baseline no-show approx. 12%, higher for patients with a previous no-show, booked more than 14 days ahead, or in early time slots. (This relationship is an assumed simulation for ML, not a medical conclusion.)
- Cancellations approx. 10%; reasons from the `cancel-reason` catalog.
- 1x/5x/10x load tests: multiply the booking rate per `docs/02-requirements/workload-analysis.md`.

## 6. Usage
- Local: `make -C dev seed-users` creates login users (patient/doctor/nurse/admin/dpo); business data is loaded with the SA generator using `--scale small --seed 42`.
- Tests: use a fixed seed in tests so results are stable; do not commit large data files, only the seed + parameters.
- CI/UAT: `scale` is chosen per environment (table in section 2); the seed is written to the job log for reproducibility.

## 7. Automated checks (added by the SA to the generator tests)
- No value matches real phone number/national ID patterns (synthetic ranges only).
- 100% referential integrity; the same seed produces the same output hash.
- No email domain other than `.test`/`example.*`.
