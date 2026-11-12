"""Generate deterministic fake FHIR clinical resources."""

import random
from typing import Any

from tools.synthetic.generators.ids import UUID7Generator

ICD10_SYSTEM = "http://hl7.org/fhir/sid/icd-10"
LOINC_SYSTEM = "http://loinc.org"

ENCOUNTER_CLASS_SYSTEM = "http://terminology.hl7.org/CodeSystem/v3-ActCode"
ENCOUNTER_DIAGNOSIS_ROLE_SYSTEM = "http://terminology.hl7.org/CodeSystem/diagnosis-role"

# Only codes declared in contracts/terminology/code-systems.yaml.
DIAGNOSES = (
    "A09",
    "E11",
    "E78.5",
    "I10",
    "I25.9",
    "J06.9",
    "J18.9",
    "J45",
    "K29.7",
    "M54.5",
    "N39.0",
    "R50.9",
)

VITAL_SIGNS = {
    "heart_rate": {
        "code": "8867-4",
        "display": "Heart rate",
        "unit": "/min",
        "system": "http://unitsofmeasure.org",
    },
    "blood_pressure": {
        "code": "85354-9",
        "display": "Blood pressure panel",
        "unit": "mm[Hg]",
        "system": "http://unitsofmeasure.org",
    },
    "temperature": {
        "code": "8310-5",
        "display": "Body temperature",
        "unit": "Cel",
        "system": "http://unitsofmeasure.org",
    },
    "respiratory_rate": {
        "code": "9279-1",
        "display": "Respiratory rate",
        "unit": "/min",
        "system": "http://unitsofmeasure.org",
    },
    "oxygen_saturation": {
        "code": "59408-5",
        "display": "Oxygen saturation by pulse oximetry",
        "unit": "%",
        "system": "http://unitsofmeasure.org",
    },
    "weight": {
        "code": "29463-7",
        "display": "Body weight",
        "unit": "kg",
        "system": "http://unitsofmeasure.org",
    },
    "height": {
        "code": "8302-2",
        "display": "Body height",
        "unit": "cm",
        "system": "http://unitsofmeasure.org",
    },
    "bmi": {
        "code": "39156-5",
        "display": "Body mass index",
        "unit": "kg/m2",
        "system": "http://unitsofmeasure.org",
    },
}

NOTE_TEMPLATES = (
    "Bệnh nhân đến khám theo lịch hẹn. Đã ghi nhận thông tin khám và sinh hiệu.",
    "Bệnh nhân được tiếp nhận tại phòng khám. Thông tin lâm sàng được ghi nhận.",
    "Hoàn tất lượt khám mô phỏng. Các chỉ số sinh hiệu đã được lưu.",
)


def _quantity(value: float, unit: str, system: str) -> dict[str, Any]:
    return {
        "value": value,
        "unit": unit,
        "system": system,
        "code": unit,
    }


def _observation(
    observation_id: str,
    patient_id: str,
    encounter_id: str,
    effective: str,
    code: str,
    display: str,
    value: float | None = None,
    unit: str | None = None,
    components: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    resource: dict[str, Any] = {
        "resourceType": "Observation",
        "id": observation_id,
        "status": "final",
        "category": [
            {
                "coding": [
                    {
                        "system": (
                            "http://terminology.hl7.org/CodeSystem/observation-category"
                        ),
                        "code": "vital-signs",
                        "display": "Vital Signs",
                    }
                ]
            }
        ],
        "code": {
            "coding": [
                {
                    "system": LOINC_SYSTEM,
                    "code": code,
                    "display": display,
                }
            ]
        },
        "subject": {
            "reference": f"Patient/{patient_id}",
        },
        "encounter": {
            "reference": f"Encounter/{encounter_id}",
        },
        "effectiveDateTime": effective,
    }

    if value is not None and unit is not None:
        resource["valueQuantity"] = _quantity(
            value,
            unit,
            "http://unitsofmeasure.org",
        )

    if components:
        resource["component"] = components

    return resource


def _generate_vitals(
    patient_id: str,
    encounter_id: str,
    effective: str,
    rng: random.Random,
    observation_ids: UUID7Generator,
    abnormal: bool,
) -> list[dict[str, Any]]:
    """Generate eight vital-sign observations per encounter."""
    observations: list[dict[str, Any]] = []

    heart_rate = rng.randint(60, 100)
    systolic = rng.randint(90, 120)
    diastolic = rng.randint(60, 80)
    temperature = round(rng.uniform(36.0, 37.5), 1)
    respiratory_rate = rng.randint(12, 20)
    oxygen = rng.randint(95, 100)

    if abnormal:
        # One simulated abnormal vital sign per selected encounter.
        abnormal_type = rng.choice(
            ("heart_rate", "blood_pressure", "temperature", "oxygen")
        )

        if abnormal_type == "heart_rate":
            heart_rate = rng.choice((45, 48, 110, 115, 120))
        elif abnormal_type == "blood_pressure":
            systolic, diastolic = rng.choice(((145, 95), (150, 100), (85, 55)))
        elif abnormal_type == "temperature":
            temperature = rng.choice((35.5, 38.2, 38.8, 39.0))
        else:
            oxygen = rng.choice((88, 90, 91, 92))

    height = round(rng.uniform(145, 190), 1)
    weight = round(rng.uniform(45, 100), 1)
    bmi = round(weight / ((height / 100) ** 2), 1)

    def add(
        key: str,
        value: float,
    ) -> None:
        metadata = VITAL_SIGNS[key]
        observations.append(
            _observation(
                observation_id=str(observation_ids.next()),
                patient_id=patient_id,
                encounter_id=encounter_id,
                effective=effective,
                code=metadata["code"],
                display=metadata["display"],
                value=value,
                unit=metadata["unit"],
            )
        )

    add("heart_rate", heart_rate)

    bp = VITAL_SIGNS["blood_pressure"]
    observations.append(
        _observation(
            observation_id=str(observation_ids.next()),
            patient_id=patient_id,
            encounter_id=encounter_id,
            effective=effective,
            code=bp["code"],
            display=bp["display"],
            components=[
                {
                    "code": {
                        "coding": [
                            {
                                "system": LOINC_SYSTEM,
                                "code": "8480-6",
                                "display": "Systolic blood pressure",
                            }
                        ]
                    },
                    "valueQuantity": _quantity(
                        systolic, "mm[Hg]", "http://unitsofmeasure.org"
                    ),
                },
                {
                    "code": {
                        "coding": [
                            {
                                "system": LOINC_SYSTEM,
                                "code": "8462-4",
                                "display": "Diastolic blood pressure",
                            }
                        ]
                    },
                    "valueQuantity": _quantity(
                        diastolic, "mm[Hg]", "http://unitsofmeasure.org"
                    ),
                },
            ],
        )
    )

    add("temperature", temperature)
    add("respiratory_rate", respiratory_rate)
    add("oxygen_saturation", oxygen)
    add("weight", weight)
    add("height", height)
    add("bmi", bmi)

    return observations


def generate_clinical_resources(
    seed: int,
    appointments: list[dict[str, Any]],
) -> tuple[
    list[dict[str, Any]],
    list[dict[str, Any]],
    list[dict[str, Any]],
]:
    """Generate Encounter, Condition and Observation resources.

    One Encounter and one Condition are generated for each fulfilled
    Appointment. Eight vital-sign Observations are generated per Encounter.
    """
    rng = random.Random(seed + 70_000)

    encounter_ids = UUID7Generator(seed=seed + 70_000)
    condition_ids = UUID7Generator(seed=seed + 80_000)
    observation_ids = UUID7Generator(seed=seed + 90_000)

    encounters: list[dict[str, Any]] = []
    conditions: list[dict[str, Any]] = []
    observations: list[dict[str, Any]] = []

    fulfilled = [
        appointment
        for appointment in appointments
        if appointment["status"] == "fulfilled"
    ]

    for appointment in fulfilled:
        encounter_id = str(encounter_ids.next())
        condition_id = str(condition_ids.next())

        patient_ref = next(
            participant["actor"]["reference"]
            for participant in appointment["participant"]
            if participant["actor"]["reference"].startswith("Patient/")
        )

        practitioner_ref = next(
            participant["actor"]["reference"]
            for participant in appointment["participant"]
            if participant["actor"]["reference"].startswith("Practitioner/")
        )

        start = appointment["start"]
        end = appointment["end"]

        diagnosis_code = rng.choice(DIAGNOSES)

        encounter = {
            "resourceType": "Encounter",
            "id": encounter_id,
            "status": "finished",
            "class": {
                "system": ENCOUNTER_CLASS_SYSTEM,
                "code": "AMB",
                "display": "ambulatory",
            },
            "appointment": [
                {
                    "reference": f"Appointment/{appointment['id']}",
                }
            ],
            "subject": {
                "reference": patient_ref,
            },
            "participant": [
                {
                    "individual": {
                        "reference": practitioner_ref,
                    }
                }
            ],
            "period": {
                "start": start,
                "end": end,
            },
            "diagnosis": [
                {
                    "condition": {
                        "reference": f"Condition/{condition_id}",
                    },
                    "use": {
                        "coding": [
                            {
                                "system": ENCOUNTER_DIAGNOSIS_ROLE_SYSTEM,
                                "code": "AD",
                                "display": "Admission diagnosis",
                            }
                        ]
                    },
                }
            ],
            "note": [
                {
                    "text": rng.choice(NOTE_TEMPLATES),
                }
            ],
        }

        condition = {
            "resourceType": "Condition",
            "id": condition_id,
            "clinicalStatus": {
                "coding": [
                    {
                        "system": (
                            "http://terminology.hl7.org/CodeSystem/condition-clinical"
                        ),
                        "code": "active",
                    }
                ]
            },
            "verificationStatus": {
                "coding": [
                    {
                        "system": (
                            "http://terminology.hl7.org/CodeSystem/condition-ver-status"
                        ),
                        "code": "confirmed",
                    }
                ]
            },
            "category": [
                {
                    "coding": [
                        {
                            "system": (
                                "http://terminology.hl7.org/CodeSystem/"
                                "condition-category"
                            ),
                            "code": "encounter-diagnosis",
                        }
                    ]
                }
            ],
            "code": {
                "coding": [
                    {
                        "system": ICD10_SYSTEM,
                        "code": diagnosis_code,
                    }
                ]
            },
            "subject": {
                "reference": patient_ref,
            },
            "encounter": {
                "reference": f"Encounter/{encounter_id}",
            },
            "onsetDateTime": start,
        }

        abnormal = rng.random() < 0.08

        encounters.append(encounter)
        conditions.append(condition)

        observations.extend(
            _generate_vitals(
                patient_id=patient_ref.split("/", 1)[1],
                encounter_id=encounter_id,
                effective=end,
                rng=rng,
                observation_ids=observation_ids,
                abnormal=abnormal,
            )
        )

    return encounters, conditions, observations
