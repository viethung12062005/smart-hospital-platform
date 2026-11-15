"""DAT-03: one command that loads synthetic data through the public APIs.

The CLI generates the DAT-01/DAT-02 dataset with a fixed ``seed`` and ``scale`` and submits it through the
documented public endpoints (clinical C3/C10 and scheduling S1/S5/S7/S11). It is idempotent: a second run
resolves the resources it created instead of creating them again. Nothing here talks to a database directly,
so every business rule, authorization check and audit record of the API applies to seeded data.

Local and cloud use the same code path; only the base URL and the role tokens differ. The cloud base URL is
never hard-coded and must be HTTPS. Tokens are read from the environment or the command line and are never
printed or written to the state file.

The API implementations for the dependencies of DAT-03 (SCH-14 ``$book`` and CLN-05 registration/staff) do
not exist in this repository yet. The CLI therefore targets their documented contracts and its tests run it
against a contract-shaped stub (see ``tests/synthetic/test_dat03.py``). A live run is not possible until
those endpoints are implemented; see ``tools/synthetic/README.md`` section 12.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import uuid
from collections import Counter
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from datetime import date, timedelta
from pathlib import Path
from typing import Any, cast
from urllib import error, parse, request

from tools.synthetic.generators.appointments import generate_appointments
from tools.synthetic.generators.patients import generate_patients
from tools.synthetic.generators.practitioners import generate_practitioners
from tools.synthetic.generators.schedules import generate_schedules

LOCAL_BASE_URL = "http://localhost:8080"
DEFAULT_STATE_ROOT = Path("dev/.local/synthetic")

FHIR_JSON = "application/fhir+json"
FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"

# Sample bookings use the nurse/staff channel: the nurse is the account that seeds data.
BOOKING_CHANNEL = "staff"
MAX_FUTURE_APPOINTMENTS = 3
SEED_DAYS = 30
STATE_VERSION = 1

# Weekly template used by S5 before S7 generates slots: Monday-Friday, 07:00-12:00 and 13:00-17:00, 30-min
# slots. Two non-overlapping ranges per weekday satisfy the S5 validation rules.
WEEKLY_TEMPLATES: list[dict[str, Any]] = [
    {
        "weekday": weekday,
        "startTime": start,
        "endTime": end,
        "slotMinutes": 30,
    }
    for weekday in (1, 2, 3, 4, 5)
    for start, end in (("07:00", "12:00"), ("13:00", "17:00"))
]

ROLE_SYSTEM_SPECIALTY = "https://shp.example.org/fhir/CodeSystem/specialty"


class SeedError(Exception):
    """A configuration or seeding error that the operator must act on."""


class SeedConfigError(SeedError):
    """Invalid CLI/environment configuration."""


@dataclass(frozen=True)
class ApiResponse:
    """An HTTP response from the public API."""

    status: int
    body: Any
    headers: Mapping[str, str]

    def header(self, name: str) -> str | None:
        lowered = name.lower()
        for key, value in self.headers.items():
            if key.lower() == lowered:
                return value
        return None


def _parse_json(raw: bytes) -> Any:
    if not raw:
        return None
    try:
        return json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return raw.decode("utf-8", "replace")


class ApiClient:
    """A small JSON/FHIR HTTP client with per-role bearer tokens."""

    def __init__(
        self,
        base_url: str,
        tokens: Mapping[str, str | None],
        *,
        timeout: float = 30.0,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._tokens = {role: token for role, token in tokens.items() if token}
        self._timeout = timeout

    def request(
        self,
        method: str,
        path: str,
        role: str,
        *,
        body: Any = None,
        form: Mapping[str, Any] | None = None,
        headers: Mapping[str, str] | None = None,
    ) -> ApiResponse:
        """Send one request. HTTP error statuses are returned, transport errors raise SeedError."""
        token = self._tokens.get(role) or self._tokens.get("default")
        if not token:
            raise SeedConfigError(
                f"Missing token for role '{role}'. Pass --{role}-token or set SHP_SEED_TOKEN."
            )

        all_headers: dict[str, str] = {
            "Accept": FHIR_JSON,
            "Authorization": f"Bearer {token}",
        }
        data: bytes | None = None

        if body is not None and form is not None:
            raise SeedConfigError(
                "A request cannot carry both a JSON body and a form body."
            )

        if form is not None:
            data = parse.urlencode(list(form.items()), doseq=True).encode("utf-8")
            all_headers["Content-Type"] = FORM_CONTENT_TYPE
        elif body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            all_headers["Content-Type"] = FHIR_JSON

        if headers:
            all_headers.update(headers)

        url = (
            path if path.startswith(("http://", "https://")) else self._base_url + path
        )
        http_request = request.Request(
            url, data=data, headers=all_headers, method=method
        )

        try:
            with request.urlopen(http_request, timeout=self._timeout) as response:
                return ApiResponse(
                    status=response.status,
                    body=_parse_json(response.read()),
                    headers=dict(response.headers),
                )
        except error.HTTPError as exc:
            return ApiResponse(
                status=exc.code,
                body=_parse_json(exc.read()),
                headers=dict(exc.headers or {}),
            )
        except error.URLError as exc:
            raise SeedError(f"Cannot reach {self._base_url}: {exc.reason}") from exc


@dataclass
class SeedState:
    """Mapping from synthetic resource keys to server ids, kept between runs."""

    path: Path
    env: str
    base_url: str
    scale: str
    seed: int
    resources: dict[str, dict[str, str]] = field(default_factory=dict)

    @classmethod
    def load(
        cls,
        path: Path,
        *,
        env: str,
        base_url: str,
        scale: str,
        seed: int,
    ) -> SeedState:
        state = cls(
            path=path,
            env=env,
            base_url=base_url,
            scale=scale,
            seed=seed,
        )
        if not path.exists():
            return state

        raw = cast(dict[str, Any], json.loads(path.read_text(encoding="utf-8")))
        if raw.get("version") != STATE_VERSION:
            raise SeedConfigError(
                f"State file {path} has an unsupported version; choose another --state-file."
            )
        identity = (
            raw.get("env"),
            raw.get("base_url"),
            raw.get("scale"),
            raw.get("seed"),
        )
        if identity != (env, base_url, scale, seed):
            raise SeedConfigError(
                f"State file {path} belongs to {identity}, not "
                f"{(env, base_url, scale, seed)}; choose another --state-file."
            )

        raw_resources = cast(dict[str, Any], raw.get("resources") or {})
        state.resources = {
            str(resource): {
                str(key): str(value)
                for key, value in cast(dict[str, Any], values).items()
            }
            for resource, values in raw_resources.items()
        }
        return state

    def get(self, resource: str, key: str) -> str | None:
        return self.resources.get(resource, {}).get(key)

    def put(self, resource: str, key: str, value: str) -> None:
        self.resources.setdefault(resource, {})[key] = value

    def forget(self, resource: str, key: str) -> None:
        values = self.resources.get(resource)
        if values is not None:
            values.pop(key, None)

    def save(self) -> None:
        payload = {
            "version": STATE_VERSION,
            "env": self.env,
            "base_url": self.base_url,
            "scale": self.scale,
            "seed": self.seed,
            "resources": self.resources,
        }
        content = (
            json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
        )
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        temporary.write_text(content, encoding="utf-8")
        temporary.replace(self.path)


@dataclass
class SeedReport:
    created: Counter[str] = field(default_factory=Counter)
    skipped: Counter[str] = field(default_factory=Counter)
    failed: Counter[str] = field(default_factory=Counter)
    errors: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.failed

    def add_error(self, resource: str, message: str) -> None:
        self.failed[resource] += 1
        if len(self.errors) < 50:
            self.errors.append(message)


@dataclass(frozen=True)
class Dataset:
    """The generated resources, ready to be mapped onto server ids."""

    patients: list[dict[str, Any]]
    practitioners: list[dict[str, Any]]
    roles: list[dict[str, Any]]
    schedules: list[dict[str, Any]]
    slots: list[dict[str, Any]]
    appointments: list[dict[str, Any]]


def build_dataset(scale: str, seed: int) -> Dataset:
    """Generate the DAT-01/DAT-02 resources in memory."""
    patients = generate_patients(scale=scale, seed=seed)
    practitioners, roles = generate_practitioners(scale=scale, seed=seed)
    schedules, slots = generate_schedules(
        scale=scale,
        seed=seed,
        practitioners=practitioners,
        practitioner_roles=roles,
    )
    appointments = generate_appointments(
        scale=scale,
        seed=seed,
        patients=patients,
        practitioners=practitioners,
        practitioner_roles=roles,
        schedules=schedules,
        slots=slots,
    )
    return Dataset(
        patients=patients,
        practitioners=practitioners,
        roles=roles,
        schedules=schedules,
        slots=slots,
        appointments=appointments,
    )


def _reference_id(reference: str) -> str:
    return reference.rsplit("/", 1)[-1]


def _resource_id(response: ApiResponse) -> str | None:
    body = response.body
    if isinstance(body, dict):
        for name in ("id", "patientId", "documentId"):
            value = body.get(name)
            if isinstance(value, str) and value:
                return value

    location = response.header("Location")
    if location:
        parts = [part for part in location.rstrip("/").split("/") if part]
        if "_history" in parts:
            index = parts.index("_history")
            if index > 0:
                return parts[index - 1]
        if parts:
            return parts[-1]
    return None


def _error_code(body: Any) -> str | None:
    if not isinstance(body, dict):
        return None
    code = body.get("code")
    if isinstance(code, str):
        return code

    issues = body.get("issue")
    if isinstance(issues, list):
        for issue in issues:
            if not isinstance(issue, dict):
                continue
            details = issue.get("details")
            if not isinstance(details, dict):
                continue
            coding = details.get("coding")
            if isinstance(coding, list):
                for item in coding:
                    if isinstance(item, dict) and isinstance(item.get("code"), str):
                        return cast(str, item["code"])
    return None


def _bundle_resources(body: Any) -> list[dict[str, Any]]:
    if not isinstance(body, dict):
        return []
    entries = body.get("entry")
    if not isinstance(entries, list):
        return []
    resources: list[dict[str, Any]] = []
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        resource = entry.get("resource")
        if isinstance(resource, dict):
            resources.append(cast(dict[str, Any], resource))
    return resources


def _next_link(body: Any) -> str | None:
    if not isinstance(body, dict):
        return None
    links = body.get("link")
    if not isinstance(links, list):
        return None
    for link in links:
        if isinstance(link, dict) and link.get("relation") == "next":
            url = link.get("url")
            if isinstance(url, str) and url:
                return url
    return None


def _first_identifier(resource: Mapping[str, Any]) -> str | None:
    identifiers = resource.get("identifier")
    if not isinstance(identifiers, list):
        return None
    for item in identifiers:
        if not isinstance(item, dict):
            continue
        system = item.get("system")
        value = item.get("value")
        if isinstance(system, str) and isinstance(value, str):
            return f"{system}|{value}"
    return None


def _role_code(role: Mapping[str, Any]) -> str | None:
    code = role.get("code")
    if not isinstance(code, list):
        return None
    for item in code:
        if not isinstance(item, dict):
            continue
        coding = item.get("coding")
        if not isinstance(coding, list):
            continue
        for entry in coding:
            if isinstance(entry, dict) and isinstance(entry.get("code"), str):
                return cast(str, entry["code"])
    return None


def _specialty_code(role: Mapping[str, Any]) -> str | None:
    specialty = role.get("specialty")
    if not isinstance(specialty, list) or not specialty:
        return None
    coding = specialty[0].get("coding") if isinstance(specialty[0], dict) else None
    if not isinstance(coding, list) or not coding:
        return None
    code = coding[0].get("code") if isinstance(coding[0], dict) else None
    return code if isinstance(code, str) else None


def _patient_reference(appointment: Mapping[str, Any]) -> str | None:
    participants = appointment.get("participant")
    if not isinstance(participants, list):
        return None
    for participant in participants:
        if not isinstance(participant, dict):
            continue
        actor = participant.get("actor")
        if isinstance(actor, dict):
            reference = actor.get("reference")
            if isinstance(reference, str) and reference.startswith("Patient/"):
                return reference
    return None


class Seeder:
    """Load the generated dataset through the public APIs, idempotently."""

    def __init__(
        self,
        client: ApiClient,
        state: SeedState,
        dataset: Dataset,
        report: SeedReport,
        *,
        today: date,
    ) -> None:
        self._client = client
        self._state = state
        self._dataset = dataset
        self._report = report
        self._today = today
        self._patients: dict[str, str] = {}
        self._practitioners: dict[str, str] = {}
        self._roles: dict[str, str] = {}
        self._schedules: dict[str, str] = {}

    def run(self) -> SeedReport:
        self.seed_patients()
        self.seed_practitioners()
        self.seed_roles()
        self.seed_schedules()
        self.seed_appointments()
        self._state.save()
        return self._report

    # ------------------------------------------------------------------ helpers

    def _existing(self, resource: str, key: str, role: str) -> str | None:
        server_id = self._state.get(resource, key)
        if not server_id:
            return None
        response = self._client.request("GET", f"/fhir/{resource}/{server_id}", role)
        if response.status == 200:
            return server_id
        if response.status == 404:
            self._state.forget(resource, key)
            return None
        self._report.add_error(
            resource,
            f"GET /fhir/{resource}/{server_id} returned HTTP {response.status} "
            f"({_error_code(response.body)})",
        )
        return None

    def _search(self, path: str, role: str) -> list[dict[str, Any]]:
        response = self._client.request("GET", path, role)
        if response.status != 200:
            return []
        return _bundle_resources(response.body)

    def _search_form(
        self, path: str, role: str, form: Mapping[str, Any]
    ) -> list[dict[str, Any]]:
        response = self._client.request("POST", path, role, form=form)
        if response.status != 200:
            return []
        return _bundle_resources(response.body)

    # ----------------------------------------------------------------- patients

    def seed_patients(self) -> None:
        for patient in self._dataset.patients:
            key = cast(str, patient["id"])
            existing = self._existing("Patient", key, "nurse")
            if existing:
                self._patients[key] = existing
                self._report.skipped["patients"] += 1
                continue

            response = self._client.request(
                "POST", "/fhir/Patient", "nurse", body=patient
            )
            if response.status in (200, 201):
                server_id = _resource_id(response)
                if not server_id:
                    self._report.add_error("patients", "Patient create returned no id")
                    continue
                self._patients[key] = server_id
                self._state.put("Patient", key, server_id)
                self._report.created["patients"] += 1
                continue

            if response.status in (409, 422) and _error_code(response.body) in {
                "duplicate-patient-suspected",
                "already-registered",
            }:
                found = self._find_patient(patient)
                if found:
                    self._patients[key] = found
                    self._state.put("Patient", key, found)
                    self._report.skipped["patients"] += 1
                else:
                    self._report.add_error(
                        "patients",
                        f"Patient {key} already exists but could not be resolved",
                    )
                continue

            self._report.add_error(
                "patients",
                f"POST /fhir/Patient returned HTTP {response.status} "
                f"({_error_code(response.body)})",
            )
        self._state.save()

    def _find_patient(self, patient: Mapping[str, Any]) -> str | None:
        identifier = _first_identifier(patient)
        if not identifier:
            return None
        matches = self._search_form(
            "/fhir/Patient/_search",
            "nurse",
            {"identifier": identifier},
        )
        return str(matches[0]["id"]) if matches else None

    # ------------------------------------------------------------- practitioners

    def seed_practitioners(self) -> None:
        for practitioner in self._dataset.practitioners:
            key = cast(str, practitioner["id"])
            existing = self._existing("Practitioner", key, "nurse")
            if existing:
                self._practitioners[key] = existing
                self._report.skipped["practitioners"] += 1
                continue

            response = self._client.request(
                "POST", "/fhir/Practitioner", "admin", body=practitioner
            )
            if response.status in (200, 201):
                server_id = _resource_id(response)
                if not server_id:
                    self._report.add_error(
                        "practitioners", "Practitioner create returned no id"
                    )
                    continue
                self._practitioners[key] = server_id
                self._state.put("Practitioner", key, server_id)
                self._report.created["practitioners"] += 1
                continue

            if response.status in (409, 422):
                found = self._find_practitioner(practitioner)
                if found:
                    self._practitioners[key] = found
                    self._state.put("Practitioner", key, found)
                    self._report.skipped["practitioners"] += 1
                else:
                    self._report.add_error(
                        "practitioners",
                        f"Practitioner {key} already exists but could not be resolved",
                    )
                continue

            self._report.add_error(
                "practitioners",
                f"POST /fhir/Practitioner returned HTTP {response.status} "
                f"({_error_code(response.body)})",
            )
        self._state.save()

    def _find_practitioner(self, practitioner: Mapping[str, Any]) -> str | None:
        identifier = _first_identifier(practitioner)
        if not identifier:
            return None
        query = parse.urlencode({"identifier": identifier})
        matches = self._search(f"/fhir/Practitioner?{query}", "nurse")
        return str(matches[0]["id"]) if matches else None

    # -------------------------------------------------------------------- roles

    def seed_roles(self) -> None:
        for role in self._dataset.roles:
            key = cast(str, role["id"])
            practitioner_synthetic = _reference_id(role["practitioner"]["reference"])
            server_practitioner = self._practitioners.get(practitioner_synthetic)
            if not server_practitioner:
                self._report.add_error(
                    "practitioner_roles",
                    f"Practitioner {practitioner_synthetic} was not seeded",
                )
                continue

            existing = self._existing_role(key, server_practitioner)
            if existing:
                self._roles[key] = existing
                self._report.skipped["practitioner_roles"] += 1
                continue

            body = dict(role)
            body["practitioner"] = {
                **cast(dict[str, Any], role["practitioner"]),
                "reference": f"Practitioner/{server_practitioner}",
            }
            response = self._client.request(
                "POST", "/fhir/PractitionerRole", "admin", body=body
            )
            if response.status in (200, 201):
                server_id = _resource_id(response)
                if not server_id:
                    self._report.add_error(
                        "practitioner_roles", "PractitionerRole create returned no id"
                    )
                    continue
                self._roles[key] = server_id
                self._state.put("PractitionerRole", key, server_id)
                self._report.created["practitioner_roles"] += 1
                continue

            if response.status in (409, 422):
                found = self._find_role(role, server_practitioner)
                if found:
                    self._roles[key] = found
                    self._state.put("PractitionerRole", key, found)
                    self._report.skipped["practitioner_roles"] += 1
                else:
                    self._report.add_error(
                        "practitioner_roles",
                        f"PractitionerRole for Practitioner/{server_practitioner} "
                        "already exists but could not be resolved",
                    )
                continue

            self._report.add_error(
                "practitioner_roles",
                f"POST /fhir/PractitionerRole returned HTTP {response.status} "
                f"({_error_code(response.body)})",
            )
        self._state.save()

    def _existing_role(self, key: str, server_practitioner: str) -> str | None:
        # PractitionerRole has no GET-by-id in the contract; resolve it from the practitioner's roles.
        if not self._state.get("PractitionerRole", key):
            return None
        role = next(
            (item for item in self._dataset.roles if item["id"] == key),
            None,
        )
        if role is None:
            return None
        return self._find_role(role, server_practitioner)

    def _find_role(
        self, role: Mapping[str, Any], server_practitioner: str
    ) -> str | None:
        query = parse.urlencode({"practitioner": f"Practitioner/{server_practitioner}"})
        matches = self._search(f"/fhir/PractitionerRole?{query}", "nurse")
        wanted = _role_code(role)
        for match in matches:
            if _role_code(match) == wanted:
                return str(match["id"])
        return None

    # ---------------------------------------------------------------- schedules

    def seed_schedules(self) -> None:
        doctor_roles: dict[str, dict[str, Any]] = {}
        for candidate_role in self._dataset.roles:
            if _role_code(candidate_role) != "doctor":
                continue
            practitioner = _reference_id(candidate_role["practitioner"]["reference"])
            doctor_roles[practitioner] = candidate_role

        first_schedule: dict[str, dict[str, Any]] = {}
        for schedule in self._dataset.schedules:
            for actor in cast(list[dict[str, Any]], schedule.get("actor", [])):
                practitioner = _reference_id(actor["reference"])
                first_schedule.setdefault(practitioner, schedule)

        for practitioner in sorted(first_schedule):
            schedule = first_schedule[practitioner]
            key = cast(str, schedule["id"])
            server_practitioner = self._practitioners.get(practitioner)
            role = doctor_roles.get(practitioner)
            if not server_practitioner or role is None:
                self._report.add_error(
                    "schedules",
                    f"Doctor {practitioner} has no seeded PractitionerRole",
                )
                continue

            server_schedule = self._existing("Schedule", key, "nurse")
            etag: str | None = None
            if server_schedule:
                self._report.skipped["schedules"] += 1
            else:
                body = {
                    "resourceType": "Schedule",
                    "active": True,
                    "actor": [{"reference": f"Practitioner/{server_practitioner}"}],
                    "specialty": [
                        {
                            "coding": [
                                {
                                    "system": ROLE_SYSTEM_SPECIALTY,
                                    "code": _specialty_code(role),
                                }
                            ]
                        }
                    ],
                    "comment": "Synthetic seed data (not a real schedule)",
                }
                response = self._client.request(
                    "POST", "/fhir/Schedule", "nurse", body=body
                )
                created_now = response.status in (200, 201)
                if created_now:
                    server_schedule = _resource_id(response)
                    etag = response.header("ETag")
                elif response.status == 409:
                    server_schedule = self._find_schedule(server_practitioner)
                else:
                    self._report.add_error(
                        "schedules",
                        f"POST /fhir/Schedule returned HTTP {response.status} "
                        f"({_error_code(response.body)})",
                    )
                    continue

                if not server_schedule:
                    self._report.add_error(
                        "schedules",
                        f"Schedule for Practitioner/{server_practitioner} "
                        "already exists but could not be resolved",
                    )
                    continue

                self._state.put("Schedule", key, server_schedule)
                if created_now:
                    self._report.created["schedules"] += 1
                else:
                    self._report.skipped["schedules"] += 1

            current = self._client.request(
                "GET", f"/fhir/Schedule/{server_schedule}", "nurse"
            )
            if current.status != 200:
                self._report.add_error(
                    "schedules",
                    f"GET /fhir/Schedule/{server_schedule} returned HTTP {current.status}",
                )
                continue
            etag = etag or current.header("ETag")
            if not etag:
                self._report.add_error(
                    "schedules",
                    f"Schedule/{server_schedule} returned no ETag; cannot set templates",
                )
                continue

            templates = [
                {**template, "validFrom": self._today.isoformat(), "validTo": None}
                for template in WEEKLY_TEMPLATES
            ]
            response = self._client.request(
                "PUT",
                f"/api/v1/scheduling/schedules/{server_schedule}/templates",
                "nurse",
                body={"templates": templates},
                headers={"If-Match": etag},
            )
            if response.status != 200:
                self._report.add_error(
                    "schedules",
                    f"PUT templates for Schedule/{server_schedule} returned HTTP "
                    f"{response.status} ({_error_code(response.body)})",
                )
                continue

            response = self._client.request(
                "POST",
                f"/api/v1/scheduling/schedules/{server_schedule}/slots:generate",
                "nurse",
                body={
                    "from": self._today.isoformat(),
                    "to": (self._today + timedelta(days=SEED_DAYS - 1)).isoformat(),
                },
            )
            if response.status != 200:
                self._report.add_error(
                    "slots",
                    f"POST slots:generate for Schedule/{server_schedule} returned HTTP "
                    f"{response.status} ({_error_code(response.body)})",
                )
                continue

            generated = response.body if isinstance(response.body, dict) else {}
            self._report.created["slots"] += int(generated.get("created", 0))
            self._report.skipped["slots"] += int(generated.get("skippedExisting", 0))
            self._schedules[practitioner] = server_schedule

        self._state.save()

    def _find_schedule(self, server_practitioner: str) -> str | None:
        query = parse.urlencode(
            {"actor": f"Practitioner/{server_practitioner}", "active": "true"}
        )
        matches = self._search(f"/fhir/Schedule?{query}", "nurse")
        return str(matches[0]["id"]) if matches else None

    # --------------------------------------------------------------- appointments

    def seed_appointments(self) -> None:
        slot_schedule = {
            cast(str, slot["id"]): _reference_id(slot["schedule"]["reference"])
            for slot in self._dataset.slots
        }
        schedule_doctor = {
            cast(str, schedule["id"]): _reference_id(actor["reference"])
            for schedule in self._dataset.schedules
            for actor in cast(list[dict[str, Any]], schedule.get("actor", []))
        }

        free_slots: dict[str, list[tuple[str, str]]] = {
            practitioner: self._fetch_free_slots(schedule_id)
            for practitioner, schedule_id in sorted(self._schedules.items())
        }
        slot_index: dict[str, int] = {practitioner: 0 for practitioner in free_slots}
        per_patient: Counter[str] = Counter()
        created_since_save = 0

        appointments_by_doctor: dict[str, list[dict[str, Any]]] = {}
        for appointment in self._dataset.appointments:
            slot_refs = cast(list[dict[str, Any]], appointment.get("slot", []))
            if not slot_refs:
                continue
            schedule = slot_schedule.get(_reference_id(slot_refs[0]["reference"]))
            doctor = schedule_doctor.get(schedule) if schedule else None
            if doctor in free_slots:
                appointments_by_doctor.setdefault(doctor, []).append(appointment)

        for doctor in sorted(appointments_by_doctor):
            candidate_slots = free_slots[doctor]
            for appointment in appointments_by_doctor[doctor]:
                key = cast(str, appointment["id"])
                patient_reference = _patient_reference(appointment)
                patient_synthetic = (
                    _reference_id(patient_reference) if patient_reference else None
                )
                server_patient = (
                    self._patients.get(patient_synthetic) if patient_synthetic else None
                )
                if not server_patient:
                    self._report.add_error(
                        "appointments", f"Appointment {key} has no seeded Patient"
                    )
                    continue

                marker = f"Synthetic seed appointment {key}"
                existing = self._existing("Appointment", key, "nurse")
                if existing:
                    self._state.put("Appointment", key, existing)
                    per_patient[cast(str, patient_synthetic)] += 1
                    self._report.skipped["appointments"] += 1
                    continue

                found = self._find_appointment(server_patient, marker)
                if found:
                    self._state.put("Appointment", key, found)
                    per_patient[cast(str, patient_synthetic)] += 1
                    self._report.skipped["appointments"] += 1
                    continue

                if per_patient[cast(str, patient_synthetic)] >= MAX_FUTURE_APPOINTMENTS:
                    self._report.skipped["appointments_capped"] += 1
                    continue

                booked = self._book(
                    appointment=appointment,
                    patient=server_patient,
                    patient_key=cast(str, patient_synthetic),
                    marker=marker,
                    slots=candidate_slots,
                    index=slot_index,
                    doctor=doctor,
                )
                if booked:
                    per_patient[cast(str, patient_synthetic)] += 1
                    created_since_save += 1
                    if created_since_save >= 25:
                        self._state.save()
                        created_since_save = 0

        self._state.save()

    def _book(
        self,
        *,
        appointment: Mapping[str, Any],
        patient: str,
        patient_key: str,
        marker: str,
        slots: list[tuple[str, str]],
        index: dict[str, int],
        doctor: str,
    ) -> bool:
        key = cast(str, appointment["id"])
        idempotency_key = str(
            uuid.uuid5(
                uuid.NAMESPACE_URL,
                f"shp-seed|{self._state.seed}|{self._state.scale}|appointment|{key}",
            )
        )
        attempts = 0
        while index[doctor] < len(slots) and attempts < 3:
            _, slot_id = slots[index[doctor]]
            index[doctor] += 1
            attempts += 1

            body = {
                "resourceType": "Parameters",
                "parameter": [
                    {
                        "name": "slot",
                        "valueReference": {"reference": f"Slot/{slot_id}"},
                    },
                    {
                        "name": "patient",
                        "valueReference": {"reference": f"Patient/{patient}"},
                    },
                    {"name": "comment", "valueString": marker},
                    {"name": "channel", "valueCode": BOOKING_CHANNEL},
                ],
            }
            response = self._client.request(
                "POST",
                "/fhir/Appointment/$book",
                "nurse",
                body=body,
                headers={"Idempotency-Key": idempotency_key},
            )
            if response.status in (200, 201):
                server_id = _resource_id(response)
                if not server_id:
                    self._report.add_error(
                        "appointments", "Book returned no appointment id"
                    )
                    return False
                self._state.put("Appointment", key, server_id)
                if response.status == 201:
                    self._report.created["appointments"] += 1
                else:
                    self._report.skipped["appointments"] += 1
                return True

            code = _error_code(response.body)
            if response.status == 409 and code == "slot-unavailable":
                continue
            if response.status == 422 and code == "max-future-appointments":
                self._report.skipped["appointments_capped"] += 1
                return False
            if response.status == 422 and code == "outside-booking-window":
                self._report.skipped["appointments_outside_window"] += 1
                return False
            if response.status == 422 and code == "idempotency-key-reused":
                self._report.add_error(
                    "appointments",
                    f"Idempotency key for Appointment {key} was reused with a different body",
                )
                return False
            self._report.add_error(
                "appointments",
                f"POST /fhir/Appointment/$book for Patient/{patient_key} returned HTTP "
                f"{response.status} ({code})",
            )
            return False

        self._report.skipped["appointments_no_slot"] += 1
        return False

    def _fetch_free_slots(self, schedule_id: str) -> list[tuple[str, str]]:
        path = f"/fhir/Slot?schedule=Schedule/{schedule_id}&status=free&_count=500"
        found: set[tuple[str, str]] = set()
        pages = 0
        while path and pages < 50:
            response = self._client.request("GET", path, "nurse")
            if response.status != 200:
                self._report.add_error(
                    "slots",
                    f"GET /fhir/Slot for Schedule/{schedule_id} returned HTTP "
                    f"{response.status}",
                )
                return []
            for slot in _bundle_resources(response.body):
                start = slot.get("start")
                slot_id = slot.get("id")
                if isinstance(start, str) and isinstance(slot_id, str):
                    found.add((start, slot_id))
            path = _next_link(response.body) or ""
            pages += 1
        return sorted(found)

    def _find_appointment(self, server_patient: str, marker: str) -> str | None:
        start = self._today.isoformat()
        end = (self._today + timedelta(days=SEED_DAYS)).isoformat()
        query = parse.urlencode(
            [
                ("patient", f"Patient/{server_patient}"),
                ("date", f"ge{start}"),
                ("date", f"le{end}"),
            ]
        )
        matches = self._search(f"/fhir/Appointment?{query}", "nurse")
        for match in matches:
            if match.get("comment") == marker:
                return str(match["id"])
        return None


def _base_url(env: str, explicit: str | None, allow_http: bool) -> str:
    candidate = explicit or os.environ.get("SHP_SEED_BASE_URL")
    if not candidate:
        if env == "local":
            candidate = LOCAL_BASE_URL
        else:
            raise SeedConfigError(
                "The cloud environment needs --base-url or SHP_SEED_BASE_URL "
                "(no cloud URL is hard-coded in the repository)."
            )

    normalized = candidate.rstrip("/")
    if env == "cloud" and not normalized.startswith("https://") and not allow_http:
        raise SeedConfigError(
            "The cloud environment must use HTTPS (pass --allow-http to override)."
        )
    if not normalized.startswith(("http://", "https://")):
        raise SeedConfigError("The base URL must start with http:// or https://.")
    return normalized


def _state_path(
    explicit: Path | None, env: str, base_url: str, scale: str, seed: int
) -> Path:
    if explicit:
        return explicit
    digest = hashlib.sha256(base_url.encode("utf-8")).hexdigest()[:12]
    return DEFAULT_STATE_ROOT / f"seed-state-{env}-{digest}-{scale}-{seed}.json"


def _print_plan(
    dataset: Dataset,
    *,
    env: str,
    base_url: str,
    scale: str,
    seed: int,
    today: date,
) -> None:
    print("DAT-03 seed plan (dry run, no HTTP request is sent)")
    print(f"  environment: {env}")
    print(f"  base URL:    {base_url}")
    print(f"  scale/seed:  {scale}/{seed}")
    print(
        f"  slot window: {today.isoformat()} to {(today + timedelta(days=SEED_DAYS - 1)).isoformat()}"
    )
    print(f"  patients: {len(dataset.patients)}")
    print(f"  practitioners: {len(dataset.practitioners)}")
    print(f"  practitioner roles: {len(dataset.roles)}")
    print(f"  schedules: {len(dataset.schedules)}")
    print(f"  appointments: {len(dataset.appointments)}")
    print("  requests, in order:")
    for line in (
        "POST /fhir/Patient                      (nurse, C3)",
        "POST /fhir/Practitioner                 (admin, C10)",
        "POST /fhir/PractitionerRole             (admin, C10)",
        "POST /fhir/Schedule                     (nurse, S1)",
        "PUT  /api/v1/scheduling/schedules/{id}/templates (nurse, S5)",
        "POST /api/v1/scheduling/schedules/{id}/slots:generate (nurse, S7)",
        "POST /fhir/Appointment/$book            (nurse, S11, Idempotency-Key)",
    ):
        print(f"    {line}")


def _print_report(report: SeedReport, state_path: Path) -> None:
    print("Synthetic seed: " + ("SUCCESS" if report.ok else "FAILED"))
    print(f"State file: {state_path}")
    for group in (
        "patients",
        "practitioners",
        "practitioner_roles",
        "schedules",
        "slots",
        "appointments",
    ):
        created = report.created.get(group, 0)
        skipped = report.skipped.get(group, 0)
        failed = report.failed.get(group, 0)
        print(f"  {group}: created={created} skipped={skipped} failed={failed}")
    for name, count in sorted(report.skipped.items()):
        if name.endswith(("_capped", "_no_slot", "_outside_window")):
            print(f"  skipped {name}: {count}")
    for message in report.errors:
        print(f"  error: {message}")


def run_seed(
    *,
    env: str,
    base_url: str,
    scale: str,
    seed: int,
    today: date,
    tokens: Mapping[str, str | None],
    state_path: Path,
    timeout: float = 30.0,
    dataset: Dataset | None = None,
) -> tuple[SeedReport, SeedState]:
    """Run the seeder once. Exposed for tests; the CLI wraps it."""
    state = SeedState.load(
        state_path,
        env=env,
        base_url=base_url,
        scale=scale,
        seed=seed,
    )
    client = ApiClient(base_url, tokens, timeout=timeout)
    report = SeedReport()
    seeder = Seeder(
        client,
        state,
        dataset or build_dataset(scale, seed),
        report,
        today=today,
    )
    seeder.run()
    return report, state


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="DAT-03: load synthetic data through the public APIs (idempotent)."
    )
    parser.add_argument("--env", choices=("local", "cloud"), default="local")
    parser.add_argument("--base-url", help="Gateway base URL; required for cloud")
    parser.add_argument(
        "--scale", choices=("small", "medium", "large"), default="small"
    )
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument(
        "--today",
        type=date.fromisoformat,
        default=date.today(),
        help="First day of the slot window (ISO date, default: today)",
    )
    parser.add_argument("--state-file", type=Path)
    parser.add_argument(
        "--token", help="Token used for every role when no role token is given"
    )
    parser.add_argument(
        "--nurse-token", help="Token for the nurse role (C3, S1, S5, S7, S11)"
    )
    parser.add_argument("--admin-token", help="Token for the admin role (C10)")
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument(
        "--allow-http", action="store_true", help="Allow HTTP for the cloud environment"
    )
    parser.add_argument(
        "--dry-run", action="store_true", help="Print the request plan and exit"
    )
    args = parser.parse_args(argv)

    try:
        base_url = _base_url(args.env, args.base_url, args.allow_http)
        state_path = _state_path(
            args.state_file, args.env, base_url, args.scale, args.seed
        )
        dataset = build_dataset(args.scale, args.seed)

        if args.dry_run:
            _print_plan(
                dataset,
                env=args.env,
                base_url=base_url,
                scale=args.scale,
                seed=args.seed,
                today=args.today,
            )
            return 0

        tokens: dict[str, str | None] = {
            "nurse": args.nurse_token
            or args.token
            or os.environ.get("SHP_SEED_NURSE_TOKEN")
            or os.environ.get("SHP_SEED_TOKEN"),
            "admin": args.admin_token
            or args.token
            or os.environ.get("SHP_SEED_ADMIN_TOKEN")
            or os.environ.get("SHP_SEED_TOKEN"),
        }
        missing = [role for role, token in tokens.items() if not token]
        if missing:
            raise SeedConfigError(
                "Missing token for role(s): "
                + ", ".join(missing)
                + ". Pass --nurse-token/--admin-token, --token, or the SHP_SEED_*_TOKEN variables."
            )

        report, _ = run_seed(
            env=args.env,
            base_url=base_url,
            scale=args.scale,
            seed=args.seed,
            today=args.today,
            tokens=tokens,
            state_path=state_path,
            timeout=args.timeout,
            dataset=dataset,
        )
    except SeedConfigError as exc:
        print(f"Configuration error: {exc}", file=sys.stderr)
        return 2
    except SeedError as exc:
        print(f"Seed error: {exc}", file=sys.stderr)
        return 1

    _print_report(report, state_path)
    return 0 if report.ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
