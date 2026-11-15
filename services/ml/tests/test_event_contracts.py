"""Producer/consumer contract tests for the no-show consumer (ML-12, 13-events §5).

The producer's example in ``contracts/events`` is the source of truth: every event type the ML consumer registers for
is read from its example file, validated against the schema shipped with ``shp_platform`` and processed by the real
``NoShowConsumer`` on a MongoDB replica set. The events built by hand in ``test_scoring_service.py`` prove the business
rules; this file proves the consumer can read what the producer actually publishes.
"""

import json
import uuid
from pathlib import Path
from typing import Any

import pytest

from shp_ml.consumer import BOOKED, CANCELLED, MERGED, RESCHEDULED, STATUS, TYPES, NoShowConsumer
from shp_ml.serving import ModelHolder
from shp_ml.store import ensure_schema
from shp_platform import events
from shp_platform.testing import fresh_db

ROOT = Path(__file__).resolve().parents[3]
CONTRACTS = ROOT / "contracts" / "events"

# event type -> "<context>/<event>" of the producer example the consumer must be able to read
CONTRACT_EXAMPLES = {
    BOOKED: "scheduling/appointment-booked",
    CANCELLED: "scheduling/appointment-cancelled",
    RESCHEDULED: "scheduling/appointment-rescheduled",
    STATUS: "scheduling/appointment-status-changed",
    MERGED: "clinical/patient-merged",
}


def producer_event(event_type: str) -> dict[str, Any]:
    """Build the event a producer publishes from its contract example, then validate it."""
    context, name = CONTRACT_EXAMPLES[event_type].split("/")
    data = json.loads((CONTRACTS / context / f"{name}.v1.example.json").read_text(encoding="utf-8"))
    subject_id = data.get("appointmentId") or data.get("targetPatientId") or str(uuid.uuid4())
    subject = f"{'Patient' if context == 'clinical' else 'Appointment'}/{subject_id}"
    evt = events.create(context, event_type, subject, data)
    events.validate(evt)
    return evt


def consumer(mongo_uri: str, name: str) -> tuple[Any, Any, NoShowConsumer]:
    """Real consumer and schema on a throwaway database; no model, so nothing is scored."""
    client, db = fresh_db(mongo_uri, name)
    ensure_schema(db)
    return client, db, NoShowConsumer(client, db, ModelHolder(None))


def test_every_consumed_type_has_a_producer_example() -> None:
    assert set(TYPES) == set(CONTRACT_EXAMPLES)


@pytest.mark.parametrize("event_type", sorted(CONTRACT_EXAMPLES))
def test_consumer_accepts_every_producer_example(mongo_uri: str, event_type: str) -> None:
    _, db, c = consumer(mongo_uri, f"ml_contract_{event_type.rsplit('.', 2)[-2]}")

    # events.parse is the validation the bus consumer runs before calling handle.
    c.handle(events.parse(json.dumps(producer_event(event_type))))

    assert db.inbox.count_documents({}) == 1


def test_booked_then_cancelled_example_marks_the_fact_cancelled(mongo_uri: str) -> None:
    _, db, c = consumer(mongo_uri, "ml_contract_cancel")
    booked = producer_event(BOOKED)
    c.handle(booked)
    c.handle(producer_event(CANCELLED))

    fact = db.appointment_facts.find_one({"_id": booked["data"]["appointmentId"]})
    assert fact["outcome"] == "cancelled"
    assert fact["version"] == 2


def test_status_example_marks_the_fact_fulfilled(mongo_uri: str) -> None:
    _, db, c = consumer(mongo_uri, "ml_contract_status")
    booked = producer_event(BOOKED)
    c.handle(booked)
    c.handle(producer_event(STATUS))

    fact = db.appointment_facts.find_one({"_id": booked["data"]["appointmentId"]})
    assert (fact["outcome"], fact["version"]) == ("fulfilled", 4)
    assert db.patient_stats.find_one({"_id": booked["data"]["patientId"]})["fulfilledTotal"] == 1


def test_rescheduled_example_creates_the_new_appointment_fact(mongo_uri: str) -> None:
    _, db, c = consumer(mongo_uri, "ml_contract_reschedule")
    booked = producer_event(BOOKED)
    rescheduled = producer_event(RESCHEDULED)
    c.handle(booked)
    c.handle(rescheduled)

    old = db.appointment_facts.find_one({"_id": booked["data"]["appointmentId"]})
    new = db.appointment_facts.find_one({"_id": rescheduled["data"]["newAppointmentId"]})
    assert old["outcome"] == "cancelled"
    assert (new["patientId"], new["channel"], new["ageBand"]) == (
        booked["data"]["patientId"],
        booked["data"]["channel"],
        booked["data"]["ageBand"],
    )


def test_patient_merged_example_moves_the_stats(mongo_uri: str) -> None:
    _, db, c = consumer(mongo_uri, "ml_contract_merge")
    booked = producer_event(BOOKED)
    merged = producer_event(MERGED)
    c.handle(booked)
    c.handle(producer_event(STATUS))
    c.handle(merged)

    source, target = merged["data"]["sourcePatientId"], merged["data"]["targetPatientId"]
    assert db.patient_stats.find_one({"_id": source}) is None
    moved = db.patient_stats.find_one({"_id": target})
    assert (moved["bookedTotal"], moved["fulfilledTotal"]) == (1, 1)
    assert db.appointment_facts.find_one({"_id": booked["data"]["appointmentId"]})["patientId"] == target


def test_consumer_ignores_forward_compatible_extra_fields(mongo_uri: str) -> None:
    # 13-events §5: adding an optional field within a version must not break a consumer.
    _, db, c = consumer(mongo_uri, "ml_contract_forward")
    evt = producer_event(BOOKED)
    evt["data"]["futureOptionalField"] = "ignored"

    events.validate(evt)  # the schema accepts additive fields
    c.handle(evt)

    assert db.appointment_facts.count_documents({"_id": evt["data"]["appointmentId"]}) == 1
