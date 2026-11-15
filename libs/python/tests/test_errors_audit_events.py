import json
import re
import uuid
from pathlib import Path
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import BaseModel

from shp_platform import audit, events
from shp_platform.errors import ShpError, all_errors, install_error_handlers
from shp_platform.ids import uuid7

ROOT = Path(__file__).resolve().parents[3]


def test_every_python_error_code_is_documented_in_07() -> None:
    table = (ROOT / "docs/03-architecture/detailed-design/07-api-conventions.md").read_text(encoding="utf-8")
    documented = set(re.findall(r"`([a-z-]+)`", table))
    missing = [e.code for e in all_errors() if e.code not in documented and e.code != "internal-error"]
    assert not missing


def test_error_handlers_never_echo_input_or_exception_text() -> None:
    class Body(BaseModel):
        phone: int

    app = FastAPI()
    install_error_handlers(app)

    @app.post("/x")
    def x(b: Body) -> dict[str, int]:
        return {"p": b.phone}

    @app.get("/boom")
    def boom() -> None:
        raise RuntimeError("secret 0912345678")

    @app.get("/busy")
    def busy() -> None:
        raise ShpError("ai-unavailable")

    c = TestClient(app, raise_server_exceptions=False)
    r = c.post("/x", json={"phone": "0912345678abc"})
    assert r.status_code == 400 and "0912345678" not in r.text and "phone" in r.json()["detail"]
    r = c.get("/boom")
    assert r.status_code == 500 and "secret" not in r.text
    r = c.get("/busy", headers={"Accept-Language": "en"})
    assert r.status_code == 503 and r.headers["Retry-After"] == "5"
    assert r.json()["detail"] == "The AI feature is unavailable."
    assert c.get("/missing").json()["code"] == "not-found"


def test_uuid7_is_version_7_and_time_ordered() -> None:
    a, b = uuid7(1_000), uuid7(2_000)
    assert a.version == 7 and a.variant == uuid.RFC_4122 and a < b


def test_audit_record_masks_ip_and_matches_schema() -> None:
    rec = audit.build(
        service="ai",
        action="ai-query",
        outcome="success",
        actor_subject="kc-1",
        actor_role="doctor",
        resource="Conversation/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e11",
        purpose_of_use="TREAT",
        client_ip="10.1.2.3",
        user_agent="x" * 300,
        patient_id=uuid.uuid4(),
    )
    assert rec["client"] == {"ip": "10.1.2.x", "userAgent": "x" * 120}
    events.create("ai", "shp.audit.recorded.v1", "AuditEvent/" + rec["eventId"], rec)
    assert audit.mask_ip("2001:db8:1:2::5") == "2001:0db8:0001::x"
    assert audit.mask_ip("example.com") is None


def test_audit_records_on_behalf_of_when_acting_for_a_linked_profile() -> None:
    rec = audit.build(
        service="clinical",
        action="read",
        outcome="success",
        actor_subject="kc-parent",
        actor_role="patient",
        resource="Encounter/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e06",
        on_behalf_of="Patient/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02",
        relationship="guardian",
        purpose_of_use="PATRQT",
    )
    assert rec["actor"]["subject"] == "kc-parent"  # the account that acted
    assert rec["onBehalfOf"] == "Patient/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02"  # the record accessed
    assert rec["relationship"] == "guardian"


def test_audit_without_delegation_has_no_on_behalf_of() -> None:
    rec = audit.build(
        service="clinical", action="read", outcome="success", actor_subject="kc-1", actor_role="doctor",
        resource="Patient/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02",
    )  # fmt: skip
    assert "onBehalfOf" not in rec


@pytest.mark.parametrize(
    ("on_behalf_of", "relationship"),
    [
        ("Patient/ok", "parent"),  # unknown relationship
        ("not-a-reference", "guardian"),
        ("Patient/has space", "guardian"),
    ],
)
def test_audit_rejects_invalid_delegation(on_behalf_of: str, relationship: str | None) -> None:
    with pytest.raises(ValueError):
        audit.build(
            service="clinical", action="read", outcome="success", actor_subject="s", actor_role="patient",
            resource="Patient/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02",
            on_behalf_of=on_behalf_of, relationship=relationship,
        )  # fmt: skip


@pytest.mark.parametrize("resource", ["Patient?name=Nguyen", "Patient/abc def", ""])
def test_audit_rejects_search_values_in_resource(resource: str) -> None:
    with pytest.raises(ValueError):
        audit.build(
            service="ai", action="search", outcome="success", actor_subject="s", actor_role="doctor", resource=resource
        )


def _booked() -> dict[str, Any]:
    example = ROOT / "contracts/events/scheduling/appointment-booked.v1.example.json"
    data: dict[str, Any] = json.loads(example.read_text(encoding="utf-8"))
    return events.create("scheduling", "shp.scheduling.appointment-booked.v1", "Appointment/" + str(uuid.uuid4()), data)


def test_create_parse_roundtrip_and_schema_errors_hide_values() -> None:
    evt = _booked()
    assert events.parse(json.dumps(evt))["id"] == evt["id"]
    evt["data"]["channel"] = 12345
    with pytest.raises(events.EventError) as e:
        events.validate(evt)
    assert "12345" not in str(e.value)


def test_consume_is_idempotent_retries_then_dead_letters() -> None:
    raw = json.dumps(_booked())
    inbox = events.InMemoryInbox()
    calls: list[str] = []
    assert events.consume(raw, "ml", lambda e: calls.append(e["id"]), inbox, 1).outcome is events.Outcome.PROCESSED
    assert events.consume(raw, "ml", lambda e: calls.append(e["id"]), inbox, 1).outcome is events.Outcome.DUPLICATE
    assert len(calls) == 1

    def fail(_: dict[str, Any]) -> None:
        raise TimeoutError

    other = json.dumps(_booked())
    assert events.consume(other, "ml", fail, inbox, 1).outcome is events.Outcome.RETRY
    assert events.consume(other, "ml", fail, inbox, events.MAX_ATTEMPTS).outcome is events.Outcome.DEAD_LETTER

    def permanent(_: dict[str, Any]) -> None:
        raise events.PermanentError

    assert events.consume(other, "ml", permanent, inbox, 1).outcome is events.Outcome.DEAD_LETTER
    assert events.consume("{bad", "ml", fail, inbox, 1).outcome is events.Outcome.DEAD_LETTER


def test_extension_members_are_added_to_the_problem_body() -> None:
    app = FastAPI()
    install_error_handlers(app)

    @app.get("/down")
    def down() -> None:
        raise ShpError("ai-unavailable", extensions={"fallback": {"type": "booking-form", "url": "/booking"}})

    r = TestClient(app).get("/down")
    assert r.status_code == 503 and r.headers["content-type"].startswith("application/problem+json")
    body = r.json()
    assert body["code"] == "ai-unavailable" and body["fallback"] == {"type": "booking-form", "url": "/booking"}


def test_extension_members_cannot_replace_standard_members() -> None:
    with pytest.raises(ValueError, match="code"):
        ShpError("ai-unavailable", extensions={"code": "something-else"})
