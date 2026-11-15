"""DAT-03 tests: the seed CLI loads sample data through the public APIs and a rerun creates no duplicates.

The services behind the public contracts (SCH-14, CLN-05) are not implemented yet, so the tests run the CLI
against a stub that follows the documented request/response rules: server-issued ids, duplicate detection by
natural key, ``ON CONFLICT`` slot generation and ``Idempotency-Key`` replay.
"""

from __future__ import annotations

import hashlib
import io
import json
import os
import re
import sys
import threading
import unittest
from contextlib import redirect_stdout
from datetime import date, datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Any, cast
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit

from tools.synthetic.generators.patients import (
    PATIENT_COUNTS,
    REFERENCE_DATE,
)
from tools.synthetic.seed import (
    Dataset,
    SeedConfigError,
    SeedReport,
    _base_url,
    build_dataset,
    main,
    run_seed,
)

FHIR_JSON = "application/fhir+json"
FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"
FAKE_PHONE = re.compile(r"\+84900\d{6}")
DATASET: Dataset = build_dataset("small", 42)
TOKENS = {"nurse": "test-nurse-authorization", "admin": "test-admin-authorization"}
TODAY = REFERENCE_DATE


def _next_id(store: StubStore, prefix: str) -> str:
    store.counter += 1
    return f"{prefix}-{store.counter}"


def _identifier_key(resource: dict[str, Any]) -> str | None:
    identifiers = resource.get("identifier")
    if not isinstance(identifiers, list):
        return None
    for item in identifiers:
        if (
            isinstance(item, dict)
            and isinstance(item.get("system"), str)
            and isinstance(item.get("value"), str)
        ):
            return f"{item['system']}|{item['value']}"
    return None


def _code(resource: dict[str, Any]) -> str | None:
    code = resource.get("code")
    if isinstance(code, list):
        for item in code:
            if not isinstance(item, dict):
                continue
            coding = item.get("coding")
            if isinstance(coding, list) and coding and isinstance(coding[0], dict):
                value = coding[0].get("code")
                if isinstance(value, str):
                    return value
    return None


def _phone_and_birth(resource: dict[str, Any]) -> tuple[str | None, str | None]:
    phone: str | None = None
    telecom = resource.get("telecom")
    if isinstance(telecom, list):
        for item in telecom:
            if isinstance(item, dict) and item.get("system") == "phone":
                value = item.get("value")
                if isinstance(value, str):
                    phone = value
    birth = resource.get("birthDate")
    return phone, birth if isinstance(birth, str) else None


class StubStore:
    """In-memory state of the contract-shaped fake API."""

    def __init__(self) -> None:
        self.counter = 0
        self.patients: dict[str, dict[str, Any]] = {}
        self.practitioners: dict[str, dict[str, Any]] = {}
        self.roles: dict[str, dict[str, Any]] = {}
        self.schedules: dict[str, dict[str, Any]] = {}
        self.schedule_templates: dict[str, list[dict[str, Any]]] = {}
        self.schedule_etags: dict[str, str] = {}
        self.slots: dict[str, dict[str, Any]] = {}
        self.appointments: dict[str, dict[str, Any]] = {}
        self.idempotency: dict[str, tuple[str, str]] = {}
        self.markers: dict[str, str] = {}
        self.requests: list[tuple[str, str]] = []

    def counts(self) -> dict[str, int]:
        return {
            "patients": len(self.patients),
            "practitioners": len(self.practitioners),
            "roles": len(self.roles),
            "schedules": len(self.schedules),
            "slots": len(self.slots),
            "appointments": len(self.appointments),
        }


class StubServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, store: StubStore) -> None:
        super().__init__(("127.0.0.1", 0), StubHandler)
        self.store = store


class StubHandler(BaseHTTPRequestHandler):
    """Minimal implementation of the public contracts used by the seeder."""

    server: StubServer

    def log_message(self, fmt: str, *args: Any) -> None:
        return

    # ---------------------------------------------------------------- plumbing

    def _store(self) -> StubStore:
        return self.server.store

    def _body(self) -> Any:
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b""
        content_type = self.headers.get("Content-Type", "")
        if content_type.startswith(FORM_CONTENT_TYPE):
            return parse_qs(raw.decode("utf-8"), keep_blank_values=True)
        if not raw:
            return None
        return json.loads(raw.decode("utf-8"))

    def _query(self) -> dict[str, list[str]]:
        return parse_qs(urlsplit(self.path).query, keep_blank_values=True)

    def _respond(
        self,
        status: int,
        body: Any,
        *,
        etag: str | None = None,
        location: str | None = None,
    ) -> None:
        raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", FHIR_JSON)
        self.send_header("Content-Length", str(len(raw)))
        if etag:
            self.send_header("ETag", etag)
        if location:
            self.send_header("Location", location)
        self.end_headers()
        self.wfile.write(raw)

    def _problem(self, status: int, code: str) -> None:
        self._respond(
            status,
            {
                "resourceType": "OperationOutcome",
                "issue": [
                    {"severity": "error", "details": {"coding": [{"code": code}]}}
                ],
            },
        )

    def _bundle(self, resources: list[dict[str, Any]]) -> dict[str, Any]:
        return {
            "resourceType": "Bundle",
            "type": "searchset",
            "entry": [{"resource": resource} for resource in resources],
        }

    def _authorized(self) -> bool:
        return self.headers.get("Authorization", "").startswith("Bearer ")

    def _path_parts(self) -> list[str]:
        return [part for part in urlsplit(self.path).path.split("/") if part]

    # --------------------------------------------------------------------- GET

    def do_GET(self) -> None:
        if not self._authorized():
            self._problem(401, "unauthenticated")
            return
        store = self._store()
        parts = self._path_parts()
        store.requests.append(("GET", self.path))

        if len(parts) == 3 and parts[:2] == ["fhir", "Patient"]:
            patient = store.patients.get(parts[2])
            if patient is None:
                self._problem(404, "not-found")
            else:
                self._respond(200, patient)
            return

        if len(parts) == 2 and parts[:2] == ["fhir", "Practitioner"]:
            practitioner_identifier = self._query().get("identifier", [None])[0]
            practitioner_matches = [
                item
                for item in store.practitioners.values()
                if practitioner_identifier is None
                or _identifier_key(item) == practitioner_identifier
            ]
            self._respond(200, self._bundle(practitioner_matches))
            return

        if len(parts) == 3 and parts[:2] == ["fhir", "Practitioner"]:
            practitioner = store.practitioners.get(parts[2])
            if practitioner is None:
                self._problem(404, "not-found")
            else:
                self._respond(200, practitioner)
            return

        if len(parts) == 2 and parts[:2] == ["fhir", "PractitionerRole"]:
            role_practitioner = self._query().get("practitioner", [None])[0]
            role_matches = [
                item
                for item in store.roles.values()
                if role_practitioner is None
                or item.get("practitioner", {}).get("reference") == role_practitioner
            ]
            self._respond(200, self._bundle(role_matches))
            return

        if len(parts) == 2 and parts[:2] == ["fhir", "Schedule"]:
            schedule_actor = self._query().get("actor", [None])[0]
            schedule_matches = [
                item
                for item in store.schedules.values()
                if schedule_actor is None
                or item.get("actor", [{}])[0].get("reference") == schedule_actor
            ]
            self._respond(200, self._bundle(schedule_matches))
            return

        if len(parts) == 3 and parts[:2] == ["fhir", "Schedule"]:
            schedule = store.schedules.get(parts[2])
            if schedule is None:
                self._problem(404, "not-found")
            else:
                self._respond(200, schedule, etag=store.schedule_etags[parts[2]])
            return

        if len(parts) == 2 and parts[:2] == ["fhir", "Slot"]:
            slot_schedule = self._query().get("schedule", [None])[0]
            slot_status = self._query().get("status", [None])[0]
            slot_matches = [
                slot
                for slot in store.slots.values()
                if (
                    slot_schedule is None
                    or slot["schedule"]["reference"] == slot_schedule
                )
                and (slot_status is None or slot["status"] == slot_status)
            ]
            slot_matches.sort(key=lambda slot: (slot["start"], slot["id"]))
            self._respond(200, self._bundle(slot_matches))
            return

        if len(parts) == 2 and parts[:2] == ["fhir", "Appointment"]:
            appointment_patient = self._query().get("patient", [None])[0]
            appointment_matches: list[dict[str, Any]] = []
            for stored_appointment in store.appointments.values():
                if appointment_patient is not None and not any(
                    participant.get("actor", {}).get("reference") == appointment_patient
                    for participant in stored_appointment.get("participant", [])
                ):
                    continue
                appointment_matches.append(stored_appointment)
            self._respond(200, self._bundle(appointment_matches))
            return

        if len(parts) == 3 and parts[:2] == ["fhir", "Appointment"]:
            stored = store.appointments.get(parts[2])
            if stored is None:
                self._problem(404, "not-found")
            else:
                self._respond(200, stored)
            return

        self._problem(404, "not-found")

    # -------------------------------------------------------------------- POST

    def do_POST(self) -> None:
        if not self._authorized():
            self._problem(401, "unauthenticated")
            return
        store = self._store()
        parts = self._path_parts()
        store.requests.append(("POST", self.path))

        if parts == ["fhir", "Patient"]:
            self._create_patient(store, self._body())
            return
        if parts == ["fhir", "Patient", "_search"]:
            self._search_patient(store, self._body())
            return
        if parts == ["fhir", "Practitioner"]:
            self._create_practitioner(store, self._body())
            return
        if parts == ["fhir", "PractitionerRole"]:
            self._create_role(store, self._body())
            return
        if parts == ["fhir", "Schedule"]:
            self._create_schedule(store, self._body())
            return
        if parts == ["fhir", "Appointment", "$book"]:
            self._book(store, self._body())
            return
        if (
            len(parts) == 6
            and parts[:4] == ["api", "v1", "scheduling", "schedules"]
            and parts[5] == "slots:generate"
        ):
            self._generate_slots(store, parts[4], self._body())
            return

        self._problem(404, "not-found")

    # --------------------------------------------------------------------- PUT

    def do_PUT(self) -> None:
        if not self._authorized():
            self._problem(401, "unauthenticated")
            return
        store = self._store()
        parts = self._path_parts()
        store.requests.append(("PUT", self.path))

        if (
            len(parts) == 6
            and parts[:4] == ["api", "v1", "scheduling", "schedules"]
            and parts[5] == "templates"
        ):
            self._set_templates(store, parts[4], self._body())
            return

        self._problem(404, "not-found")

    # ------------------------------------------------------------------ create

    def _create_patient(self, store: StubStore, body: Any) -> None:
        if not isinstance(body, dict):
            self._problem(400, "validation-failed")
            return
        identifier = _identifier_key(body)
        phone, birth = _phone_and_birth(body)
        for existing in store.patients.values():
            if identifier and _identifier_key(existing) == identifier:
                self._problem(422, "duplicate-patient-suspected")
                return
            if phone and birth and _phone_and_birth(existing) == (phone, birth):
                self._problem(422, "duplicate-patient-suspected")
                return
        server_id = _next_id(store, "pat")
        body["id"] = server_id
        store.patients[server_id] = body
        self._respond(
            201,
            body,
            etag='W/"1"',
            location=f"/fhir/Patient/{server_id}/_history/1",
        )

    def _search_patient(self, store: StubStore, form: Any) -> None:
        if not isinstance(form, dict):
            self._problem(400, "validation-failed")
            return
        identifier = form.get("identifier", [None])[0]
        matches = [
            item
            for item in store.patients.values()
            if _identifier_key(item) == identifier
        ]
        self._respond(200, self._bundle(matches))

    def _create_practitioner(self, store: StubStore, body: Any) -> None:
        if not isinstance(body, dict):
            self._problem(400, "validation-failed")
            return
        identifier = _identifier_key(body)
        for existing in store.practitioners.values():
            if identifier and _identifier_key(existing) == identifier:
                self._problem(409, "duplicate-resource")
                return
        server_id = _next_id(store, "prac")
        body["id"] = server_id
        store.practitioners[server_id] = body
        self._respond(
            201,
            body,
            etag='W/"1"',
            location=f"/fhir/Practitioner/{server_id}/_history/1",
        )

    def _create_role(self, store: StubStore, body: Any) -> None:
        if not isinstance(body, dict):
            self._problem(400, "validation-failed")
            return
        practitioner = body.get("practitioner", {}).get("reference")
        code = _code(body)
        for existing in store.roles.values():
            if (
                existing.get("practitioner", {}).get("reference") == practitioner
                and _code(existing) == code
            ):
                self._problem(409, "duplicate-resource")
                return
        server_id = _next_id(store, "role")
        body["id"] = server_id
        store.roles[server_id] = body
        self._respond(
            201,
            body,
            etag='W/"1"',
            location=f"/fhir/PractitionerRole/{server_id}/_history/1",
        )

    def _create_schedule(self, store: StubStore, body: Any) -> None:
        if not isinstance(body, dict):
            self._problem(400, "validation-failed")
            return
        actor = body.get("actor", [{}])[0].get("reference")
        specialty = body.get("specialty", [{}])[0].get("coding", [{}])[0].get("code")
        for existing in store.schedules.values():
            existing_actor = existing.get("actor", [{}])[0].get("reference")
            existing_specialty = (
                existing.get("specialty", [{}])[0].get("coding", [{}])[0].get("code")
            )
            if active_pair(existing_actor, existing_specialty) == active_pair(
                actor, specialty
            ):
                self._problem(409, "duplicate-schedule")
                return
        server_id = _next_id(store, "sched")
        body["id"] = server_id
        store.schedules[server_id] = body
        store.schedule_etags[server_id] = 'W/"1"'
        store.schedule_templates[server_id] = []
        self._respond(
            201,
            body,
            etag=store.schedule_etags[server_id],
            location=f"/fhir/Schedule/{server_id}/_history/1",
        )

    # -------------------------------------------------------------- scheduling

    def _set_templates(self, store: StubStore, schedule_id: str, body: Any) -> None:
        if schedule_id not in store.schedules:
            self._problem(404, "not-found")
            return
        if self.headers.get("If-Match") != store.schedule_etags[schedule_id]:
            self._problem(412, "version-conflict")
            return
        if not isinstance(body, dict) or not isinstance(body.get("templates"), list):
            self._problem(400, "validation-failed")
            return
        store.schedule_templates[schedule_id] = cast(
            list[dict[str, Any]], body["templates"]
        )
        store.schedule_etags[schedule_id] = 'W/"2"'
        self._respond(
            200,
            {"templates": body["templates"], "affectedFutureSlots": 0},
            etag=store.schedule_etags[schedule_id],
        )

    def _generate_slots(self, store: StubStore, schedule_id: str, body: Any) -> None:
        if schedule_id not in store.schedules:
            self._problem(404, "not-found")
            return
        if not isinstance(body, dict):
            self._problem(400, "validation-failed")
            return
        start = date.fromisoformat(cast(str, body["from"]))
        end = date.fromisoformat(cast(str, body["to"]))
        created = 0
        skipped = 0
        current = start
        while current <= end:
            for template in store.schedule_templates[schedule_id]:
                if current.isoweekday() != template["weekday"]:
                    continue
                begin = datetime.combine(
                    current,
                    datetime.strptime(template["startTime"], "%H:%M").time(),
                )
                finish = datetime.combine(
                    current,
                    datetime.strptime(template["endTime"], "%H:%M").time(),
                )
                step = timedelta(minutes=template["slotMinutes"])
                cursor = begin
                while cursor + step <= finish:
                    key = (schedule_id, cursor.isoformat())
                    if not any(
                        (
                            slot["schedule"]["reference"].rsplit("/", 1)[-1],
                            slot["start"],
                        )
                        == key
                        for slot in store.slots.values()
                    ):
                        server_id = _next_id(store, "slot")
                        store.slots[server_id] = {
                            "resourceType": "Slot",
                            "id": server_id,
                            "schedule": {"reference": f"Schedule/{schedule_id}"},
                            "status": "free",
                            "start": cursor.isoformat(),
                            "end": (cursor + step).isoformat(),
                        }
                        created += 1
                    else:
                        skipped += 1
                    cursor += step
            current += timedelta(days=1)
        self._respond(200, {"created": created, "skippedExisting": skipped})

    # ------------------------------------------------------------- appointments

    def _book(self, store: StubStore, body: Any) -> None:
        key = self.headers.get("Idempotency-Key")
        if not key:
            self._problem(428, "idempotency-key-required")
            return
        if not isinstance(body, dict):
            self._problem(400, "validation-failed")
            return

        digest = hashlib.sha256(
            json.dumps(body, sort_keys=True, ensure_ascii=False).encode("utf-8")
        ).hexdigest()
        if key in store.idempotency:
            original_digest, original_id = store.idempotency[key]
            if original_digest != digest:
                self._problem(422, "idempotency-key-reused")
                return
            self._respond(200, store.appointments[original_id])
            return

        parameters = {
            item.get("name"): item
            for item in body.get("parameter", [])
            if isinstance(item, dict)
        }
        slot_ref = (
            parameters.get("slot", {}).get("valueReference", {}).get("reference", "")
        )
        patient_ref = (
            parameters.get("patient", {}).get("valueReference", {}).get("reference", "")
        )
        comment = parameters.get("comment", {}).get("valueString", "")
        slot_id = slot_ref.rsplit("/", 1)[-1]
        patient_id = patient_ref.rsplit("/", 1)[-1]

        if slot_id not in store.slots or patient_id not in store.patients:
            self._problem(404, "not-found")
            return
        if comment and comment in store.markers:
            self._respond(200, store.appointments[store.markers[comment]])
            return
        if store.slots[slot_id]["status"] != "free":
            self._problem(409, "slot-unavailable")
            return
        future = sum(
            1
            for appointment in store.appointments.values()
            if any(
                participant.get("actor", {}).get("reference") == patient_ref
                for participant in appointment.get("participant", [])
            )
        )
        if future >= 3:
            self._problem(422, "max-future-appointments")
            return

        server_id = _next_id(store, "appt")
        appointment = {
            "resourceType": "Appointment",
            "id": server_id,
            "status": "booked",
            "start": store.slots[slot_id]["start"],
            "end": store.slots[slot_id]["end"],
            "comment": comment,
            "slot": [{"reference": f"Slot/{slot_id}"}],
            "participant": [
                {"actor": {"reference": patient_ref}, "status": "accepted"},
            ],
        }
        store.appointments[server_id] = appointment
        store.slots[slot_id]["status"] = "busy"
        store.idempotency[key] = (digest, server_id)
        if comment:
            store.markers[comment] = server_id
        self._respond(
            201,
            appointment,
            etag='W/"1"',
            location=f"/fhir/Appointment/{server_id}/_history/1",
        )


def active_pair(actor: Any, specialty: Any) -> tuple[Any, Any]:
    return (actor, specialty)


def _start_server() -> tuple[StubServer, str, threading.Thread]:
    store = StubStore()
    server = StubServer(store)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    host, port = cast(tuple[str, int], server.server_address[:2])
    return server, f"http://{host}:{port}", thread


class SeedCliTest(unittest.TestCase):
    def setUp(self) -> None:
        self.server, self.base_url, self.thread = _start_server()
        self.addCleanup(self._stop_server)
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.state_path = Path(self.temporary.name) / "seed-state.json"

    def _stop_server(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def _run(self, state_path: Path | None = None) -> SeedReport:
        report, _ = run_seed(
            env="local",
            base_url=self.base_url,
            scale="small",
            seed=42,
            today=TODAY,
            tokens=TOKENS,
            state_path=state_path or self.state_path,
            dataset=DATASET,
        )
        return report

    def test_loads_sample_data_and_rerun_creates_no_duplicates(self) -> None:
        first = self._run()
        self.assertTrue(first.ok, first.errors)
        self.assertEqual(first.created["patients"], PATIENT_COUNTS["small"])
        self.assertEqual(first.created["practitioners"], 12)
        self.assertEqual(first.created["practitioner_roles"], 12)
        self.assertEqual(first.created["schedules"], 8)
        self.assertGreater(first.created["slots"], 0)
        self.assertGreater(first.created["appointments"], 0)

        counts_after_first = self.server.store.counts()

        second = self._run()
        self.assertTrue(second.ok, second.errors)
        self.assertEqual(sum(second.created.values()), 0)
        self.assertEqual(second.skipped["patients"], PATIENT_COUNTS["small"])
        self.assertEqual(second.skipped["practitioners"], 12)
        self.assertEqual(second.skipped["practitioner_roles"], 12)
        self.assertEqual(second.skipped["schedules"], 8)
        self.assertEqual(self.server.store.counts(), counts_after_first)

    def test_rerun_without_state_file_still_creates_no_duplicates(self) -> None:
        first = self._run()
        self.assertTrue(first.ok, first.errors)
        counts_after_first = self.server.store.counts()
        self.state_path.unlink()

        second = self._run()
        self.assertTrue(second.ok, second.errors)
        self.assertEqual(sum(second.created.values()), 0)
        self.assertEqual(self.server.store.counts(), counts_after_first)

    def test_seeded_data_is_synthetic_and_referentially_valid(self) -> None:
        report = self._run()
        self.assertTrue(report.ok, report.errors)
        store = self.server.store

        mrns = {_identifier_key(patient) for patient in store.patients.values()}
        self.assertEqual(len(mrns), len(store.patients))
        phones: set[str] = set()
        for patient in store.patients.values():
            phone, _ = _phone_and_birth(patient)
            self.assertIsNotNone(phone)
            assert phone is not None
            self.assertRegex(phone, FAKE_PHONE)
            phones.add(phone)
            emails = [
                item["value"]
                for item in patient.get("telecom", [])
                if item.get("system") == "email"
            ]
            self.assertTrue(emails)
            self.assertTrue(all(email.endswith("@example.test") for email in emails))
        self.assertEqual(len(phones), len(store.patients))

        for appointment in store.appointments.values():
            patient_ref = appointment["participant"][0]["actor"]["reference"]
            slot_ref = appointment["slot"][0]["reference"]
            self.assertIn(patient_ref.rsplit("/", 1)[-1], store.patients)
            slot_id = slot_ref.rsplit("/", 1)[-1]
            self.assertIn(slot_id, store.slots)
            self.assertEqual(store.slots[slot_id]["status"], "busy")
            self.assertTrue(
                appointment["comment"].startswith("Synthetic seed appointment ")
            )

    def test_idempotency_keys_are_reused_on_rerun(self) -> None:
        first = self._run()
        self.assertTrue(first.ok, first.errors)
        keys_after_first = set(self.server.store.idempotency)
        self.assertEqual(len(keys_after_first), len(self.server.store.appointments))

        self.state_path.unlink()
        second = self._run()
        self.assertTrue(second.ok, second.errors)
        self.assertEqual(set(self.server.store.idempotency), keys_after_first)

    def test_state_file_does_not_contain_tokens(self) -> None:
        self._run()
        content = self.state_path.read_text(encoding="utf-8")
        self.assertNotIn(TOKENS["nurse"], content)
        self.assertNotIn(TOKENS["admin"], content)

    def test_dry_run_sends_no_request(self) -> None:
        output = io.StringIO()
        with redirect_stdout(output):
            code = main(
                [
                    "--env",
                    "local",
                    "--base-url",
                    self.base_url,
                    "--scale",
                    "small",
                    "--seed",
                    "42",
                    "--dry-run",
                ]
            )
        self.assertEqual(code, 0)
        self.assertIn("POST /fhir/Patient", output.getvalue())
        self.assertIn("POST /fhir/Appointment/$book", output.getvalue())
        self.assertEqual(self.server.store.requests, [])
        self.assertEqual(self.server.store.counts()["patients"], 0)

    def test_cloud_requires_base_url_and_https(self) -> None:
        with patch.dict(os.environ, {}, clear=True), self.assertRaises(SeedConfigError):
            _base_url("cloud", None, False)
        with self.assertRaises(SeedConfigError):
            _base_url("cloud", "http://shp.example.test", False)
        self.assertEqual(
            _base_url("cloud", "https://shp.example.test/", False),
            "https://shp.example.test",
        )

        with patch.dict(os.environ, {}, clear=True):
            stderr = io.StringIO()
            with redirect_stdout(io.StringIO()), patch.object(sys, "stderr", stderr):
                code = main(["--env", "cloud", "--dry-run"])
        self.assertEqual(code, 2)
        self.assertIn("base-url", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
