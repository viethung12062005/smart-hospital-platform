"""US-058: record summary (AI-19..AI-21)."""

import json
import re
from collections.abc import Sequence
from typing import Any

import httpx

from shp_ai.directory import StaticDirectory
from shp_platform.llm import ChatMessage, FakeProvider

from .conftest import DOCTOR, NURSE, bearer, fake_providers

PID = "11111111-1111-4111-8111-111111111111"
URL = f"/api/v1/ai/patients/{PID}/summary"


class FakeExchanger:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str]] = []

    def exchange(self, token: str, audience: str) -> str:
        self.calls.append((token[:10], audience))
        return f"obo-{audience}"


def clinical(seen: list[str], deny: bool = False) -> httpx.Client:
    def handler(req: httpx.Request) -> httpx.Response:
        seen.append(req.headers["authorization"])
        if deny:
            return httpx.Response(403, json={"code": "no-care-relationship"})
        path = req.url.path
        if path.endswith("/Condition"):
            return httpx.Response(
                200,
                json={
                    "entry": [
                        {
                            "resource": {
                                "id": "c1",
                                "code": {"coding": [{"code": "I10", "display": "Tăng huyết áp"}]},
                                "clinicalStatus": {"coding": [{"code": "active"}]},
                                "onsetDateTime": "2024-03-01",
                            }
                        }
                    ]
                },
            )
        if path.endswith("/AllergyIntolerance"):
            return httpx.Response(
                200,
                json={
                    "entry": [
                        {
                            "resource": {
                                "id": "a1",
                                "code": {"coding": [{"display": "penicillin"}]},
                                "criticality": "high",
                                "recordedDate": "2025-01-02",
                            }
                        }
                    ]
                },
            )
        if path.endswith("/Observation"):
            return httpx.Response(
                200,
                json={
                    "entry": [
                        {
                            "resource": {
                                "id": "o1",
                                "code": {"coding": [{"display": "Glucose"}]},
                                "valueQuantity": {"value": 7.8, "unit": "mmol/L"},
                                "interpretation": [{"coding": [{"code": "H"}]}],
                                "effectiveDateTime": "2026-11-20",
                            }
                        }
                    ]
                },
            )
        if path.endswith("/timeline"):
            return httpx.Response(
                200,
                json={
                    "items": [
                        {
                            "time": "2026-11-20T02:15:00Z",
                            "type": "encounter",
                            "id": "e1",
                            "title": "Khám Tim mạch",
                            "summary": "Đau ngực khi gắng sức",
                            "practitioner": {"display": "BS. Lê Minh Quân"},
                            "ref": "Encounter/e1",
                        }
                    ]
                },
            )
        return httpx.Response(404)

    return httpx.Client(transport=httpx.MockTransport(handler), base_url="http://clinical")


def llm_summary(msgs: Sequence[ChatMessage]) -> str:
    keys = dict(re.findall(r'<record id="(E\d+)" type="(\w+)"', msgs[-1].content))
    by_type = {t: k for k, t in keys.items()}
    return json.dumps(
        {
            "chronicConditions": [
                {"text": "Tăng huyết áp (I10) đang theo dõi", "sources": [by_type["condition"]]},
                {"text": "Đái tháo đường", "sources": []},
            ],  # no source -> dropped
            "allergies": [{"text": "Dị ứng penicillin mức độ cao", "sources": [by_type["allergy"]]}],
            "recentVisits": [{"text": "Khám tim mạch vì đau ngực khi gắng sức", "sources": [by_type["encounter"]]}],
            "abnormalResults": [
                {"text": "Glucose 9,5 mmol/L", "sources": [by_type["observation"]]},  # fabricated number -> dropped
                {"text": "Glucose 7.8 mmol/L cao", "sources": [by_type["observation"]]},
            ],
        },
        ensure_ascii=False,
    )


def _api(api: Any, providers: Any = None, deny: bool = False) -> tuple[Any, Any, list[str], FakeExchanger]:
    seen: list[str] = []
    ex = FakeExchanger()
    directory = StaticDirectory({"dr-cardio": ("cardiology",)}, {("dr-cardio", PID)}, {("dr-bg", PID)})
    tc, deps = api(providers=providers, directory=directory, exchanger=ex, clinical_http=clinical(seen, deny))
    deps.settings.clinical_url = "http://clinical"
    return tc, deps, seen, ex


def test_summary_keeps_only_sourced_items_and_uses_obo(api: Any) -> None:
    ds = FakeProvider("deepseek", reply=llm_summary)
    tc, deps, seen, ex = _api(api, fake_providers(deepseek=ds))
    with tc:
        r = tc.post(URL, json={}, headers={**bearer(**DOCTOR), "Idempotency-Key": "k1"})
        body = r.json()
        assert r.status_code == 200, body
        assert body["label"] == "Do AI tạo, cần bác sĩ kiểm tra" and body["droppedItems"] == 2
        assert body["sections"]["chronicConditions"] == [
            {"text": "Tăng huyết áp (I10) đang theo dõi", "sources": ["Condition/c1"]}
        ]
        assert [i["sources"] for i in body["sections"]["abnormalResults"]] == [["Observation/o1"]]
        assert set(seen) == {"Bearer obo-clinical"} and ex.calls[0][1] == "clinical"  # the doctor's token, aud clinical
        assert "Lê Minh Quân" not in ds.received[0][-1].content  # doctor name redacted before leaving
        audit = deps.db.outbox.find_one({"eventType": "shp.audit.recorded.v1"})["payload"]["data"]
        assert audit["action"] == "ai-summary" and audit["purposeOfUse"] == "TREAT" and audit["patientId"] == PID


def test_idempotency_replay_and_cache(api: Any) -> None:
    ds = FakeProvider("deepseek", reply=llm_summary)
    tc, deps, _, _ = _api(api, fake_providers(deepseek=ds))
    with tc:
        h = {**bearer(**DOCTOR), "Idempotency-Key": "k2"}
        a = tc.post(URL, json={}, headers=h).json()
        b = tc.post(URL, json={}, headers=h).json()
        assert a == b and len(ds.received) == 1
        c = tc.post(URL, json={}, headers={**h, "Idempotency-Key": "k3"}).json()
        assert c["generatedAt"] == a["generatedAt"] and len(ds.received) == 1  # cached 10 minutes per data version
        r = tc.post(URL, json={"scope": {"from": "2026-01-01"}}, headers=h)
        assert r.status_code == 422 and r.json()["code"] == "idempotency-key-reused"
        assert tc.post(URL, json={}, headers=bearer(**DOCTOR)).status_code == 428


def test_all_models_fail_returns_structured_timeline(api: Any) -> None:
    fails = [FakeProvider(n, external=n != "ollama", failures=["server"] * 3) for n in ("deepseek", "gemini", "ollama")]
    tc, _, _, _ = _api(api, fails)
    with tc:
        body = tc.post(URL, json={}, headers={**bearer(**DOCTOR), "Idempotency-Key": "k4"}).json()
        assert body["mode"] == "structured-timeline" and body["label"].startswith("Danh sách có cấu trúc")
        assert body["sections"]["abnormalResults"][0]["sources"] == ["Observation/o1"]
        assert body["sections"]["recentVisits"][0]["sources"] == ["Encounter/e1"]


def test_permissions(api: Any) -> None:
    tc, deps, _, _ = _api(api)
    with tc:
        other = bearer(sub="dr-other", roles=["doctor"])
        r = tc.post(URL, json={}, headers={**other, "Idempotency-Key": "k5"})
        assert r.status_code == 403 and r.json()["code"] == "no-care-relationship"
        assert tc.post(URL, json={}, headers={**bearer(**NURSE), "Idempotency-Key": "k6"}).status_code == 403
        bg = bearer(sub="dr-bg", roles=["doctor"])
        assert tc.post(URL, json={}, headers={**bg, "Idempotency-Key": "k7"}).status_code == 200
        purposes = [o["payload"]["data"].get("purposeOfUse") for o in deps.db.outbox.find({})]
        assert "ETREAT" in purposes


def test_clinical_denial_is_passed_through(api: Any) -> None:
    tc, _, _, _ = _api(api, deny=True)
    with tc:
        r = tc.post(URL, json={}, headers={**bearer(**DOCTOR), "Idempotency-Key": "k8"})
        assert r.status_code == 403 and r.json()["code"] == "no-care-relationship"
        again = tc.post(URL, json={}, headers={**bearer(**DOCTOR), "Idempotency-Key": "k8"})
        assert again.status_code == 403  # the key was released on error, not stuck in progress
