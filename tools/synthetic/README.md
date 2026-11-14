# DAT-01 – Vietnamese Synthetic Data Generator

## 1. Introduction

DAT-01 is the Vietnamese fake-data generator for the Smart Hospital Platform project.

Goals:
- Generate fake data for patients, doctors and nurses.
- Never use real personal data or medical records.
- Support several data scales for development and testing.
- Guarantee reproducibility: the same `seed` and `scale` must produce the same data.
- Guarantee referential integrity between resources.

## 2. Resource list and scale

| Resource | Small | Medium | Large |
|---|---:|---:|---:|
| Patient | 200 | 20.000 | 500.000 |
| Practitioner | 12 | 120 | 600 |
| PractitionerRole | 12 | 120 | 600 |

Where:
- **Patient:** fake patient information.
- **Practitioner:** healthcare staff information.
- **PractitionerRole:** the professional role of healthcare staff, covering doctors (`doctor`) and nurses (`nurse`).

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
    └── practitioners.py

tests/
└── synthetic/
    └── test_generators.py
```

What each component does:

| File | Purpose |
|---|---|
| `generate.py` | Command-line interface, coordinates data generation and builds the manifest |
| `generators/ids.py` | Deterministic UUIDv7 generation |
| `generators/names.py` | Vietnamese surnames, middle names and given names by gender; email slugs |
| `generators/patients.py` | Generate Patient data |
| `generators/practitioners.py` | Generate Practitioner and PractitionerRole |
| `tests/synthetic/test_generators.py` | Tests for the generators |

## 4. Environment requirements

- Python >= 3.12.
- No third-party libraries are required for data generation.
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

### 5.4. Parameters

| Parameter | Meaning | Default |
|---|---|---|
| `--scale` | Data scale: `small`, `medium`, `large` | `small` |
| `--seed` | Integer used as the random seed | `42` |
| `--output-dir` | Root directory for the data | `dev/.local/synthetic` |

You can change the output directory:

```bash
python3 -m tools.synthetic.generate \
    --scale small \
    --seed 42 \
    --output-dir /tmp/synthetic-data
```

## 6. Output data structure

Data is stored as follows:

```text
dev/.local/synthetic/
├── small-seed-42/
│   ├── patients.json
│   ├── practitioners.json
│   ├── practitioner_roles.json
│   └── manifest.json
├── medium-seed-42/
│   ├── patients.json
│   ├── practitioners.json
│   ├── practitioner_roles.json
│   └── manifest.json
└── large-seed-42/
    ├── patients.json
    ├── practitioners.json
    ├── practitioner_roles.json
    └── manifest.json
```

What each file contains:

| File | Content |
|---|---|
| `patients.json` | Patient list |
| `practitioners.json` | Practitioner list |
| `practitioner_roles.json` | PractitionerRole list |
| `manifest.json` | Generator information, scale, seed, record counts and SHA-256 |

Data files are UTF-8 encoded to support Vietnamese with diacritics.

Generated data is a development and testing artifact for the local machine and is not committed to Git.

## 7. Reproducibility and identifiers

The generator uses a random seed to guarantee:
- The same `scale` and `seed` produce the same data.
- A different `seed` produces different data.
- UUIDv7 values are generated deterministically from the seed.
- A SHA-256 is computed for each data file to support integrity and reproducibility checks.

The manifest records:
- Generator name.
- Scale.
- Seed.
- Count of each resource.
- SHA-256 of each data file.

## 8. Testing

Tests run with `pytest` (or `unittest`), automatically in CI in the `synthetic` job of the `qa` workflow (ruff, mypy strict, tests):

```bash
pip install -r tools/contracts/requirements.txt ruff mypy pytest types-PyYAML
ruff check tools/synthetic tests/synthetic && ruff format --check tools/synthetic tests/synthetic
mypy --strict --explicit-package-bases tools/synthetic tests/synthetic
python -m pytest -q tests/synthetic
```

Test groups (per `docs/00-synthetic-data.md` §7):

| Group | Content |
|---|---|
| UUIDv7 | UUID version, reproducibility, different seed means different values |
| Patient | Counts; unique ID/MRN/email/phone; phone only within the fake range; `.test` email; names by gender; 49/51 gender ratio; age distribution 18/35/29/18; valid birth dates; fake markers `(test)`, `(giả)` |
| Phone number range | 500,000 unique numbers for `large`; the first 100,000 numbers are in the original `09000xxxxx` range |
| Practitioner / Role | IDs unique among themselves and against Patient; references exist; specialty and role come from `contracts/terminology`; names by gender |
| Reproducibility | The same seed + scale produce byte-identical output files |

## 9. Agreed conventions

| Item | Convention |
|---|---|
| Full name | Middle and given names chosen by gender (`names.py`); 49/51 gender ratio |
| Phone number | `+84900` + 6 digits in patient order, no duplicates. `small`/`medium` stay in the ARC-11 `09000xxxxx` range; `large` extends to `0900xxxxxx` (documented in `docs/00-synthetic-data.md` §3) |
| Patient email | `<slug>-<sequence number>@example.test` (sequence number added to avoid duplicates) |
| Practitioner email | `<slug>-nv<staff code>@example.test` |
| Doctor/nurse ratio | 2:1 |
| Role code | CodeSystem `practitioner-role` (`https://shp.example.org/fhir/CodeSystem/practitioner-role`) in `contracts/terminology`, matching 01-domain-model §3 |

## 10. Usage notes

- Use generated data only for development, demos and testing.
- Do not feed real data into the generator.
- Do not commit generated JSON data files.
- Keep the `seed` and `scale` to regenerate the same data set.
- Check the manifest and SHA-256 when you need to verify the data.
- When changing the generator, re-run the automated tests and check reproducibility.

---

**Module:** DAT-01 – Vietnamese Synthetic Data Generator
**Project:** Smart Hospital Platform
**Output:** Patient, Practitioner, PractitionerRole
**Default seed:** 42
