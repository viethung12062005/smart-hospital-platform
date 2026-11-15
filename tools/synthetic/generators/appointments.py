"""Generate deterministic fake FHIR Appointment resources."""

import random
from collections import defaultdict
from datetime import datetime, timedelta
from typing import Any

from tools.synthetic.generators.ids import UUID7Generator
from tools.synthetic.generators.patients import REFERENCE_DATE

APPOINTMENT_COUNTS = {
    "small": 600,
    "medium": 100_000,
    "large": 2_000_000,
}

BOOKING_CHANNELS = ("web", "chatbot", "staff")

CANCELLATION_REASONS = (
    "patient-request",
    "practitioner-unavailable",
    "duplicate",
    "rescheduled",
    "system-noshow-cleanup",
    "other",
)

SPECIALTY_SYSTEM = "https://shp.example.org/fhir/CodeSystem/specialty"
CANCEL_REASON_SYSTEM = "https://shp.example.org/fhir/CodeSystem/cancel-reason"
BOOKING_CHANNEL_EXTENSION = (
    "https://shp.example.org/fhir/StructureDefinition/booking-channel"
)


def _get_specialty(role: dict[str, Any]) -> str:
    """Get specialty code from a PractitionerRole."""
    try:
        code = role["specialty"][0]["coding"][0]["code"]
    except (KeyError, IndexError, TypeError):
        raise ValueError(
            f"PractitionerRole không có specialty hợp lệ: {role.get('id')}"
        ) from None

    if not isinstance(code, str):
        raise TypeError(
            f"PractitionerRole có specialty code không hợp lệ: {role.get('id')}"
        )

    return code


def _get_period(start: datetime) -> str:
    """Classify a slot into peak or normal hours."""
    current = start.time()

    if (
        7 <= current.hour < 9
        or (current.hour == 13 and current.minute >= 30)
        or current.hour == 14
    ):
        return "peak"

    return "normal"


def _allocate_slots(
    slots: list[dict[str, Any]],
    count: int,
    rng: random.Random,
) -> list[dict[str, Any]]:
    """Select unique slots with weekday and peak-hour weighting."""
    groups: dict[tuple[int, str], list[dict[str, Any]]] = defaultdict(list)

    for slot in slots:
        if slot.get("status") != "free":
            continue

        start = datetime.fromisoformat(slot["start"])
        groups[(start.weekday(), _get_period(start))].append(slot)

    available_count = sum(len(group) for group in groups.values())

    if count > available_count:
        raise ValueError(f"Không đủ Slot trống: cần {count}, hiện có {available_count}")

    weights: dict[tuple[int, str], float] = {}

    for key, group in groups.items():
        weekday, period = key

        # Monday is busier than other weekdays.
        weekday_weight = 1.5 if weekday == 0 else 1.0

        # Morning and afternoon peak hours are more popular.
        period_weight = 2.0 if period == "peak" else 1.0

        weights[key] = len(group) * weekday_weight * period_weight

    total_weight = sum(weights.values())

    allocations: dict[tuple[int, str], int] = {}
    fractions: list[tuple[float, tuple[int, str]]] = []
    allocated = 0

    for key, weight in weights.items():
        exact = count * weight / total_weight
        amount = min(int(exact), len(groups[key]))

        allocations[key] = amount
        allocated += amount
        fractions.append((exact - int(exact), key))

    fractions.sort(reverse=True)

    while allocated < count:
        progressed = False

        for _, key in fractions:
            if allocations[key] < len(groups[key]):
                allocations[key] += 1
                allocated += 1
                progressed = True

                if allocated == count:
                    break

        if not progressed:
            raise ValueError("Không thể phân bổ đủ Slot")

    selected: list[dict[str, Any]] = []

    for key, group in groups.items():
        amount = allocations[key]

        if amount:
            selected.extend(rng.sample(group, amount))

    rng.shuffle(selected)
    return selected


def generate_appointments(
    scale: str,
    seed: int,
    patients: list[dict[str, Any]],
    practitioners: list[dict[str, Any]],
    practitioner_roles: list[dict[str, Any]],
    schedules: list[dict[str, Any]],
    slots: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Generate appointments referencing existing clinical resources."""
    if scale not in APPOINTMENT_COUNTS:
        raise ValueError(f"Scale không hợp lệ: {scale}")

    if not patients:
        raise ValueError("Danh sách Patient không được rỗng")

    if not slots:
        raise ValueError("Danh sách Slot không được rỗng")

    count = APPOINTMENT_COUNTS[scale]

    rng = random.Random(seed + 60_000)
    appointment_ids = UUID7Generator(seed=seed + 60_000)

    practitioner_by_id = {
        practitioner["id"]: practitioner for practitioner in practitioners
    }

    specialty_by_practitioner: dict[str, str] = {}

    for role in practitioner_roles:
        practitioner_ref = role["practitioner"]["reference"]
        practitioner_id = practitioner_ref.split("/", 1)[1]
        role_code = role["code"][0]["coding"][0]["code"]

        if role_code == "doctor":
            if practitioner_id not in practitioner_by_id:
                raise ValueError(
                    f"PractitionerRole tham chiếu Practitioner không tồn tại: "
                    f"{practitioner_id}"
                )

            specialty_by_practitioner[practitioner_id] = _get_specialty(role)

    # Map each Schedule to its existing doctor.
    practitioner_by_schedule: dict[str, str] = {}

    for schedule in schedules:
        schedule_id = schedule["id"]

        for actor in schedule.get("actor", []):
            reference = actor["reference"]

            if not reference.startswith("Practitioner/"):
                continue

            practitioner_id = reference.split("/", 1)[1]

            if practitioner_id not in practitioner_by_id:
                raise ValueError(
                    f"Schedule tham chiếu Practitioner không tồn tại: {practitioner_id}"
                )

            if practitioner_id not in specialty_by_practitioner:
                raise ValueError(
                    f"Practitioner trong Schedule không có vai trò doctor: "
                    f"{practitioner_id}"
                )

            practitioner_by_schedule[schedule_id] = practitioner_id

    selected_slots = _allocate_slots(slots, count, rng)

    appointments: list[dict[str, Any]] = []
    previously_noshow: set[str] = set()

    for slot in selected_slots:
        schedule_ref = slot["schedule"]["reference"]
        schedule_id = schedule_ref.split("/", 1)[1]

        if schedule_id not in practitioner_by_schedule:
            raise ValueError(
                f"Slot tham chiếu Schedule không tồn tại hoặc không có bác sĩ: "
                f"{schedule_id}"
            )

        practitioner_id = practitioner_by_schedule[schedule_id]

        patient = rng.choice(patients)
        patient_id = patient["id"]

        start = datetime.fromisoformat(slot["start"])
        end = datetime.fromisoformat(slot["end"])

        available_lead = max(
            0,
            (start.date() - REFERENCE_DATE).days,
        )

        lead_days = rng.randint(0, min(30, available_lead))

        created = start - timedelta(
            days=lead_days,
            hours=rng.randint(1, 12),
        )

        # Cancellation baseline: approximately 10%.
        if rng.random() < 0.10:
            status = "cancelled"
            reason = rng.choice(CANCELLATION_REASONS)
        else:
            # No-show baseline: approximately 12%.
            no_show_probability = 0.12

            if patient_id in previously_noshow:
                no_show_probability += 0.04

            if lead_days > 14:
                no_show_probability += 0.03

            if start.hour < 9:
                no_show_probability += 0.02

            if rng.random() < no_show_probability:
                status = "noshow"
                reason = None
                previously_noshow.add(patient_id)
            else:
                status = "fulfilled"
                reason = None

        channel = rng.choices(
            BOOKING_CHANNELS,
            weights=(5, 3, 2),
            k=1,
        )[0]

        specialty = specialty_by_practitioner[practitioner_id]

        appointment: dict[str, Any] = {
            "resourceType": "Appointment",
            "id": str(appointment_ids.next()),
            "status": status,
            "created": created.isoformat(),
            "start": start.isoformat(),
            "end": end.isoformat(),
            "slot": [
                {
                    "reference": f"Slot/{slot['id']}",
                }
            ],
            "specialty": [
                {
                    "coding": [
                        {
                            "system": SPECIALTY_SYSTEM,
                            "code": specialty,
                        }
                    ]
                }
            ],
            "participant": [
                {
                    "actor": {
                        "reference": f"Patient/{patient_id}",
                    },
                    "status": "accepted",
                },
                {
                    "actor": {
                        "reference": f"Practitioner/{practitioner_id}",
                    },
                    "status": "accepted",
                },
            ],
            "extension": [
                {
                    "url": BOOKING_CHANNEL_EXTENSION,
                    "valueCode": channel,
                }
            ],
        }

        if reason is not None:
            appointment["cancelationReason"] = {
                "coding": [
                    {
                        "system": CANCEL_REASON_SYSTEM,
                        "code": reason,
                    }
                ]
            }

        appointments.append(appointment)

    return appointments
