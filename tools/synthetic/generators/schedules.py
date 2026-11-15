"""Generate deterministic fake FHIR Schedule and Slot resources."""

from datetime import date, datetime, time, timedelta, timezone
from typing import Any

from tools.synthetic.generators.ids import UUID7Generator
from tools.synthetic.generators.patients import REFERENCE_DATE

SCHEDULE_DAYS = {
    "small": 30,
    "medium": 90,
    "large": 365,
}

# Mỗi Schedule có planning horizon tối đa 30 ngày.
SCHEDULE_WINDOW_DAYS = 30

# Múi giờ địa phương Asia/Ho_Chi_Minh (UTC+7).
LOCAL_TZ = timezone(timedelta(hours=7))

# Lịch làm việc 07:00–12:00 và 13:00–17:00.
WORK_PERIODS = (
    (time(7, 0), time(12, 0)),
    (time(13, 0), time(17, 0)),
)

# Mỗi Slot kéo dài 30 phút.
SLOT_DURATION_MINUTES = 30


def _local_datetime(day: date, value: time) -> datetime:
    """Combine a date and local time with UTC+7 timezone."""
    return datetime.combine(day, value, tzinfo=LOCAL_TZ)


def _generate_daily_slots(day: date) -> list[tuple[datetime, datetime]]:
    """Return all 30-minute working slots for one day."""
    result: list[tuple[datetime, datetime]] = []

    for start_time, end_time in WORK_PERIODS:
        current = _local_datetime(day, start_time)
        period_end = _local_datetime(day, end_time)

        while current + timedelta(minutes=SLOT_DURATION_MINUTES) <= period_end:
            slot_end = current + timedelta(minutes=SLOT_DURATION_MINUTES)
            result.append((current, slot_end))
            current = slot_end

    return result


def generate_schedules(
    scale: str,
    seed: int,
    practitioners: list[dict[str, Any]],
    practitioner_roles: list[dict[str, Any]],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Generate Schedule and Slot resources linked to existing doctors."""
    if scale not in SCHEDULE_DAYS:
        raise ValueError(
            f"Scale không hợp lệ: {scale}. Chọn một trong: {', '.join(SCHEDULE_DAYS)}"
        )

    days = SCHEDULE_DAYS[scale]
    end_date = REFERENCE_DATE + timedelta(days=days)

    practitioner_by_id = {
        practitioner["id"]: practitioner for practitioner in practitioners
    }

    doctor_ids = {
        role["practitioner"]["reference"].split("/", 1)[1]
        for role in practitioner_roles
        if any(
            coding["code"] == "doctor"
            for item in role.get("code", [])
            for coding in item.get("coding", [])
        )
    }

    if not doctor_ids.issubset(practitioner_by_id):
        raise ValueError("PractitionerRole tham chiếu Practitioner không tồn tại")

    schedule_ids = UUID7Generator(seed=seed + 40_000)
    slot_ids = UUID7Generator(seed=seed + 50_000)

    schedules: list[dict[str, Any]] = []
    slots: list[dict[str, Any]] = []

    for practitioner_id in sorted(doctor_ids):
        window_start = REFERENCE_DATE

        while window_start < end_date:
            window_end = min(
                window_start + timedelta(days=SCHEDULE_WINDOW_DAYS),
                end_date,
            )

            schedule_id = str(schedule_ids.next())

            schedules.append(
                {
                    "resourceType": "Schedule",
                    "id": schedule_id,
                    "active": True,
                    "actor": [
                        {
                            "reference": f"Practitioner/{practitioner_id}",
                        }
                    ],
                    "planningHorizon": {
                        "start": _local_datetime(window_start, time.min).isoformat(),
                        "end": _local_datetime(window_end, time.min).isoformat(),
                    },
                }
            )

            current_day = window_start

            while current_day < window_end:
                for start, end in _generate_daily_slots(current_day):
                    slots.append(
                        {
                            "resourceType": "Slot",
                            "id": str(slot_ids.next()),
                            "schedule": {
                                "reference": f"Schedule/{schedule_id}",
                            },
                            "status": "free",
                            "start": start.isoformat(),
                            "end": end.isoformat(),
                            "overbooked": False,
                        }
                    )

                current_day += timedelta(days=1)

            window_start = window_end

    return schedules, slots
