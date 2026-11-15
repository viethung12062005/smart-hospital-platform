# DAT-01 + DAT-02 – Vietnamese Synthetic Data Generator

## 1. Introduction

DAT-01 is the Vietnamese fake-data generator for the Smart Hospital Platform project. DAT-02 extends it with
schedules, appointments, encounters, diagnoses, vital signs and sample documents.

Goals:
- Generate fake data for patients, doctors, nurses and the clinical resources around them.
- Never use real personal data or medical records.
- Support several data scales for development and testing.
- Guarantee reproducibility: the same `seed` and `scale` must produce the same data.
- Guarantee referential integrity between resources.
- Take codes from the project terminology contract so clinical codes stay consistent.

## 2. Resource list and scale

| Resource | Small | Medium | Large |
|---|---:|---:|---:|
| Patient | 200 | 20.000 | 500.000 |
| Practitioner | 12 | 120 | 600 |
| PractitionerRole | 12 | 120 | 600 |
| Schedule | By practitioner count and period | By practitioner count and period | By practitioner count and period |
| Slot | By practitioner count and working days | By practitioner count and working days | By practitioner count and working days |
| Appointment | 600 | 100.000 | 2.000.000 |
| Encounter | By fulfilled Appointment count | By fulfilled Appointment count | By fulfilled Appointment count |
| Condition | By Encounter count | By Encounter count | By Encounter count |
| Observation | 8 per Encounter | 8 per Encounter | 8 per Encounter |
| DocumentReference | 100 | 5.000 | 50.000 |
| Document files | 100 | 5.000 | 50.000 |

Where:
- **Patient:** fake patient information.
- **Practitioner:** healthcare staff information.
- **PractitionerRole:** the professional role of healthcare staff, covering doctors (`doctor`) and nurses (`nurse`).
- **Schedule:** a doctor's working schedule.
- **Slot:** bookable time windows.
- **Appointment:** a booking between a patient and a staff member.
- **Encounter:** a visit created from a `fulfilled` Appointment.
- **Condition:** fake diagnosis information attached to a visit.
- **Observation:** fake vital-sign measurements.
- **DocumentReference:** metadata pointing at a sample document.

The Large counts are the generator's configured scale and have not been verified by an actual run.

## 3. Directory structure

```text
tools/synthetic/
├── README.md
├── generate.py
└── generators/
    ├── __init__.py
    ├── ids.py
    ├── names.py
    ├── patients.py
    ├── practitioners.py
    ├── schedules.py
    ├── appointments.py
    ├── clinical.py
    └── documents.py

tests/
└── synthetic/
    ├── test_generators.py
    └── test_dat02.py
```

What each component does:

| File | Purpose |
|---|---|
| `generate.py` | Command-line interface, coordinates data generation and builds the manifest |
| `generators/ids.py` | Deterministic UUIDv7 generation |
| `generators/names.py` | Vietnamese surnames, middle names and given names by gender; email slugs |
| `generators/patients.py` | Generate Patient data |
| `generators/practitioners.py` | Generate Practitioner and PractitionerRole |
| `generators/schedules.py` | Generate Schedule and Slot |
| `generators/appointments.py` | Generate Appointment and the appointment statuses |
| `generators/clinical.py` | Generate Encounter, Condition and Observation |
| `generators/documents.py` | Generate DocumentReference and the SVG document contents |
| `tests/synthetic/test_generators.py` | Tests for DAT-01 and reproducibility |
| `tests/synthetic/test_dat02.py` | Tests for DAT-02 and referential integrity |

## 4. Environment requirements

- Python >= 3.12.
- Install the dependencies declared in `tools/contracts/requirements.txt`.
- Run commands from the repository root.

## 5. Usage

### 5.1. Generate Small data

```bash
python3 -m tools.synthetic.generate --scale small --seed 42
```

### 5.2. Generate Medium data

```bash
python3 -m tools.synthetic.generate --scale medium --seed 42
```

### 5.3. Generate Large data

```bash
python3 -m tools.synthetic.generate --scale large --seed 42
```

Note: Large generates 500.000 Patient and 2.000.000 Appointment records. Consider RAM, disk space and run
time before running it.

### 5.4. Parameters

| Parameter | Meaning | Default |
|---|---|---|
| `--scale` | Data scale: `small`, `medium`, `large` | `small` |
| `--seed` | Integer used as the random seed | `42` |
| `--output-dir` | Root directory for the output | `dev/.local/synthetic` |

The output directory can be changed:

```bash
python3 -m tools.synthetic.generate \
    --scale small \
    --seed 42 \
    --output-dir /tmp/synthetic-data
```

## 6. Output structure

Data is written under:

```text
dev/.local/synthetic/
├── small-seed-42/
│   ├── patients.json
│   ├── practitioners.json
│   ├── practitioner_roles.json
│   ├── schedules.json
│   ├── slots.json
│   ├── appointments.json
│   ├── encounters.json
│   ├── conditions.json
│   ├── observations.json
│   ├── document_references.json
│   ├── documents/
│   │   ├── <uuid-1>.svg
│   │   ├── <uuid-2>.svg
│   │   └── ...
│   └── manifest.json
├── medium-seed-42/
│   ├── ... (same files)
└── large-seed-42/
    ├── ... (same files)
```

What each file holds:

| File | Content |
|---|---|
| `patients.json` | Patient resources |
| `practitioners.json` | Practitioner resources |
| `practitioner_roles.json` | PractitionerRole resources |
| `schedules.json` | Schedule resources |
| `slots.json` | Slot resources |
| `appointments.json` | Appointment resources |
| `encounters.json` | Encounter resources |
| `conditions.json` | Condition resources |
| `observations.json` | Observation resources |
| `document_references.json` | Metadata of the sample documents |
| `documents/` | The generated SVG documents |
| `manifest.json` | Generator, scale, seed, record counts and SHA-256 |

All data files are UTF-8 so Vietnamese diacritics survive.

### 6.1. Verified output

These figures come from runs with `seed=42`:

| Resource | Small | Medium |
|---|---:|---:|
| Patient | 200 | 20.000 |
| Practitioner | 12 | 120 |
| PractitionerRole | 12 | 120 |
| Schedule | 8 | 240 |
| Slot | 4.320 | 129.600 |
| Appointment | 600 | 100.000 |
| Encounter | 448 | 76.822 |
| Condition | 448 | 76.822 |
| Observation | 3.584 | 614.576 |
| DocumentReference | 100 | 5.000 |
| Document files | 100 | 5.000 |

Size and run time:

| Scale | Output size | Time |
|---|---:|---:|
| Small | Not recorded separately | Not recorded |
| Medium | About 943 MiB | 1 minute 21 seconds |
| Large | Not tested | Not tested |

Large is the configured scale and has not been run in full. Schedule and Slot counts depend on the number of
practitioners and the period; Encounter, Condition and Observation counts depend on how many Appointments are
`fulfilled`.

Generated data is a local development and test artifact and is not committed to Git.

### 6.2. Manifest

`manifest.json` stores:
- `generator`: generator name.
- `scale`: data scale.
- `seed`: seed used to reproduce the data.
- `counts`: number of resources generated.
- `sha256`: SHA-256 of every JSON file and of the document set.

## 7. Reproducibility and identifiers

The generator uses a random seed to guarantee:
- The same `scale` and `seed` produce the same data.
- Different seeds produce different data.
- UUIDv7 identifiers are generated deterministically from the seed.
- SHA-256 is computed per data file for integrity and reproducibility checks.

The manifest records the generator, scale, seed, per-resource counts and the SHA-256 of each file.

## 8. Testing

Tests run with `pytest` (or `unittest`) and in CI in the `synthetic` job of the `qa` workflow (ruff, mypy
strict, tests):

```bash
pip install -r tools/contracts/requirements.txt ruff mypy pytest types-PyYAML
ruff check tools/synthetic tests/synthetic && ruff format --check tools/synthetic tests/synthetic
mypy --strict --explicit-package-bases tools/synthetic tests/synthetic
python -m pytest -q tests/synthetic
```

DAT-01 test groups:

| Group | Content |
|---|---|
| UUIDv7 | Version, reproducibility, different seed gives different ids |
| Patient | Counts; unique id/MRN/email/phone; phone only in the fake range; `.test` email; names follow gender; 49/51 gender ratio; 18/35/29/18 age bands; valid birth dates; fake markers `(test)`, `(giả)` |
| Phone range | 500.000 unique numbers for `large`; the first 100.000 stay in the original `09000xxxxx` range |
| Practitioner / Role | Ids unique within and across resources; references exist; specialty and role come from `contracts/terminology`; names follow gender |
| Reproducibility | Same seed + scale give byte-identical output files |

DAT-02 test groups:

| Group | Content |
|---|---|
| Schedule / Slot | Practitioner and Schedule references exist |
| Appointment | Patient, Practitioner and Slot references exist; status and cancel reason are valid |
| Encounter | Only generated from `fulfilled` Appointments |
| Condition | Patient and Encounter references exist |
| Observation | Patient and Encounter references exist; LOINC codes are valid |
| DocumentReference | Patient, Practitioner and Encounter references exist |
| Attachment | File path and size are exact; the document carries the fake-data watermark |
| Ids | Unique within each resource group |
| Terminology | Emitted codes belong to the CodeSystems in the terminology contract |
| Reproducibility | Same seed + scale give the same file contents |

Run the DAT-02 tests alone:

```bash
python3 -m unittest tests.synthetic.test_dat02 -v
```

Run all synthetic tests:

```bash
python3 -m unittest discover -s tests/synthetic -v
```

## 9. Settled conventions

| Topic | Convention |
|---|---|
| Names | Middle and given names follow gender (`names.py`); 49/51 gender ratio |
| Phone | `+84900` + 6 digits by patient order, unique. `small`/`medium` stay in ARC-11's `09000xxxxx`; `large` extends to `0900xxxxxx` (recorded in `docs/00-synthetic-data.md` §3) |
| Patient email | `<slug>-<sequence>@example.test` (the sequence keeps it unique) |
| Practitioner email | `<slug>-nv<staff number>@example.test` |
| Doctor/nurse ratio | 2:1 |
| Role code | `practitioner-role` CodeSystem (`https://shp.example.org/fhir/CodeSystem/practitioner-role`) in `contracts/terminology`, matching 01-domain-model §3 |
| Working hours | 07:00–12:00 and 13:00–17:00; each Slot lasts 30 minutes |
| Schedule period | Split into windows of at most 30 days |
| Appointment | Weighted towards peak hours and Mondays; simulated cancel and no-show rates |
| Encounter | Only created for `fulfilled` Appointments |
| Observation | 8 Observations per Encounter |
| Documents | SVG carrying the watermarks `DỮ LIỆU GIẢ` and `KHÔNG PHẢI HỒ SƠ Y TẾ THẬT` |

## 10. Usage notes

- Use the generated data only for development, demos and tests.
- Never feed real data into the generator.
- Do not commit generated data under `dev/.local/synthetic/`.
- Keep `seed` and `scale` so the same dataset can be regenerated.
- Check the manifest and SHA-256 when data integrity matters.
- After changing a generator or the terminology contract, rerun the automated tests and the reproducibility check.
- For the Large scale, assess RAM, disk space and run time before generating.

## 11. DAT-02 – Synthetic clinical data generator

DAT-02 extends DAT-01 with schedules, appointments, encounters, diagnoses, vital signs and sample documents.

### 11.1. Generator structure

| File | Purpose |
|---|---|
| `generators/schedules.py` | Generate Schedule and Slot |
| `generators/appointments.py` | Generate Appointment and the appointment statuses |
| `generators/clinical.py` | Generate Encounter, Condition and Observation |
| `generators/documents.py` | Generate DocumentReference and the SVG document contents |
| `tests/synthetic/test_dat02.py` | Tests for DAT-02 and referential integrity |

### 11.2. Reference rules

- Schedule references an existing Practitioner.
- Slot references an existing Schedule.
- Appointment references an existing Patient, Practitioner and Slot.
- Encounter is only generated for `fulfilled` Appointments.
- Condition references an existing Patient and Encounter.
- Observation references an existing Patient and Encounter.
- DocumentReference references an existing Patient, Practitioner and Encounter.
- The DocumentReference attachment points at the generated document file.

Slot status is updated after appointments are generated:
- `busy` for `fulfilled` or `noshow` Appointments.
- `free` for `cancelled` or unbooked Appointments.

### 11.3. Terminology

Codes come from `contracts/terminology/code-systems.yaml`:

| CodeSystem | Purpose |
|---|---|
| `specialty` | Medical specialty |
| `cancel-reason` | Cancellation reason |
| `booking-channel` | Booking channel |
| `practitioner-role` | Healthcare staff role |
| `loinc-vital-signs` | Vital-sign codes |
| `loinc-document-types` | Document type codes |
| `icd10-subset` | Diagnosis codes |

The URLs and code sets come from the project contract. The LOINC and ICD-10 sets are subsets for this project,
not the complete official catalogues.

### 11.4. Sample documents

DAT-02 generates SVG documents carrying the watermarks:
- `DỮ LIỆU GIẢ`
- `KHÔNG PHẢI HỒ SƠ Y TẾ THẬT`

Each DocumentReference carries metadata, the document type, the subject, the author, the related encounter and
the attachment path.

### 11.5. DAT-02 usage notes

- Use the generated data only for development, demos and tests.
- Never use real medical records or personal information.
- Do not commit generated data under `dev/.local/synthetic/`.
- Keep `seed` and `scale` to regenerate the data.
- After changing a generator or the terminology contract, rerun the automated tests and the reproducibility check.
