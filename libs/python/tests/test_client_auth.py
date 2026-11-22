"""IAM-02: a service authenticates with its ServiceAccount token (client assertion) or, as a fallback, a secret."""

from pathlib import Path
from urllib.parse import parse_qs

import httpx
import pytest

from shp_platform.client_auth import ASSERTION_TYPE
from shp_platform.errors import ShpError
from shp_platform.service_token import ServiceTokenProvider
from shp_platform.token_exchange import ExchangeSettings, TokenExchanger


def capture(calls: list[httpx.Request]) -> httpx.Client:
    def handler(req: httpx.Request) -> httpx.Response:
        calls.append(req)
        return httpx.Response(200, json={"access_token": "t", "expires_in": 300})

    return httpx.Client(transport=httpx.MockTransport(handler))


def test_service_token_sends_the_assertion_and_no_secret(tmp_path: Path) -> None:
    f = tmp_path / "token"
    f.write_text("k8s-sa-token-1\n")
    calls: list[httpx.Request] = []
    t = [0.0]
    p = ServiceTokenProvider(
        "https://kc/token", "svc-ai", "", capture(calls), clock=lambda: t[0], assertion_file=str(f)
    )
    assert p.token("clinical") == "t"
    body = parse_qs(calls[0].content.decode())
    assert body["grant_type"] == ["client_credentials"]
    assert body["scope"] == ["call-clinical"]
    assert body["client_id"] == ["svc-ai"]
    assert body["client_assertion_type"] == [ASSERTION_TYPE]
    assert body["client_assertion"] == ["k8s-sa-token-1"]
    assert "authorization" not in calls[0].headers and "client_secret" not in body
    # The kubelet rotates the file in place: the next request after expiry reads the new content.
    f.write_text("k8s-sa-token-2")
    t[0] = 400
    p.token("clinical")
    assert parse_qs(calls[1].content.decode())["client_assertion"] == ["k8s-sa-token-2"]


def test_secret_fallback_uses_basic_auth() -> None:
    calls: list[httpx.Request] = []
    ServiceTokenProvider("https://kc/token", "svc-ai", "s3", capture(calls)).token("clinical")
    assert calls[0].headers["authorization"].startswith("Basic ")
    assert "client_assertion" not in parse_qs(calls[0].content.decode())


def test_token_exchange_can_use_the_assertion(tmp_path: Path) -> None:
    f = tmp_path / "token"
    f.write_text("sa")
    calls: list[httpx.Request] = []
    TokenExchanger(ExchangeSettings("https://kc/token", "shp-api", assertion_file=str(f)), capture(calls)).exchange(
        "u", "clinical"
    )
    body = parse_qs(calls[0].content.decode())
    assert body["scope"] == ["exchange-clinical"] and body["client_id"] == ["shp-api"]
    assert (
        body["client_assertion"] == ["sa"] and body["subject_token"] == ["u"] and body["audience"] == ["svc-clinical"]
    )


def test_missing_assertion_file_is_a_dependency_error(tmp_path: Path) -> None:
    p = ServiceTokenProvider("https://kc/token", "svc-ai", "", capture([]), assertion_file=str(tmp_path / "absent"))
    with pytest.raises(ShpError) as e:
        p.token("clinical")
    assert e.value.info.code == "dependency-unavailable"


def test_workload_tokens_are_cached_per_target() -> None:
    calls: list[httpx.Request] = []
    provider = ServiceTokenProvider("https://kc/token", "svc-scheduling", "s3", capture(calls))
    for target in ("clinical", "ml", "clinical", "ml"):
        provider.token(target)
    assert [parse_qs(call.content.decode())["scope"] for call in calls] == [["call-clinical"], ["call-ml"]]
