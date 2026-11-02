"""Dựng bản ghi audit.recorded (E11) cho ai/ml (PY-04). Quy tắc giống org.shp.events.audit.AuditRecordBuilder.

- `resource` chỉ chứa loại, id hoặc TÊN tham số tìm kiếm, không bao giờ có giá trị (có thể là PII).
- Không bao giờ chứa nội dung câu hỏi, câu trả lời hay prompt gửi LLM.
- IP che octet cuối (IPv4) hoặc chỉ giữ 3 nhóm đầu (IPv6); user-agent cắt 120 ký tự.
"""

import ipaddress
import re
import uuid
from datetime import UTC, datetime
from typing import Any

from shp_platform.ids import uuid7

ACTIONS = frozenset(
    {
        "read",
        "search",
        "create",
        "update",
        "delete",
        "download-url",
        "ai-query",
        "ai-summary",
        "break-glass-start",
        "export",
        "login",
    }
)
OUTCOMES = frozenset({"success", "denied", "error"})
ACTOR_ROLES = frozenset({"patient", "doctor", "nurse", "admin", "dpo", "system"})
PURPOSES = frozenset({"TREAT", "ETREAT", "HOPERAT", "PATRQT"})
MAX_REASON_LENGTH = 200
MAX_USER_AGENT_LENGTH = 120
EVENT_TYPE = "shp.audit.recorded.v1"

_RESOURCE = re.compile(r"^[A-Za-z]+(/[A-Za-z0-9-]{1,64})?(\?[A-Za-z_:.,-]+(&[A-Za-z_:.,-]+)*)?$")


def mask_ip(ip: str | None) -> str | None:
    if not ip:
        return None
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return None
    if isinstance(addr, ipaddress.IPv4Address):
        a, b, c, _ = addr.packed
        return f"{a}.{b}.{c}.x"
    p = addr.packed
    return f"{p[0]:02x}{p[1]:02x}:{p[2]:02x}{p[3]:02x}:{p[4]:02x}{p[5]:02x}::x"


def build(
    *,
    service: str,
    action: str,
    outcome: str,
    actor_subject: str,
    actor_role: str,
    resource: str,
    actor_fhir_user: str | None = None,
    patient_id: uuid.UUID | None = None,
    purpose_of_use: str | None = None,
    reason: str | None = None,
    trace_id: str | None = None,
    client_ip: str | None = None,
    user_agent: str | None = None,
) -> dict[str, Any]:
    if action not in ACTIONS:
        raise ValueError(f"action không hợp lệ: {action}")
    if outcome not in OUTCOMES:
        raise ValueError(f"outcome không hợp lệ: {outcome}")
    if actor_role not in ACTOR_ROLES:
        raise ValueError(f"actor role không hợp lệ: {actor_role}")
    if not _RESOURCE.match(resource):
        raise ValueError("resource chỉ được chứa loại, id hoặc TÊN tham số tìm kiếm (không có giá trị)")
    if purpose_of_use is not None and purpose_of_use not in PURPOSES:
        raise ValueError(f"purposeOfUse không hợp lệ: {purpose_of_use}")
    if reason is not None and len(reason) > MAX_REASON_LENGTH:
        raise ValueError(f"reason dài quá {MAX_REASON_LENGTH} ký tự")
    actor: dict[str, Any] = {"subject": actor_subject, "role": actor_role}
    if actor_fhir_user:
        actor["fhirUser"] = actor_fhir_user
    record: dict[str, Any] = {
        "eventId": str(uuid7()),
        "occurredAt": datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
        "service": service,
        "action": action,
        "outcome": outcome,
        "actor": actor,
        "patientId": str(patient_id) if patient_id else None,
        "resource": resource,
        "reason": reason,
    }
    if purpose_of_use:
        record["purposeOfUse"] = purpose_of_use
    if trace_id:
        record["traceId"] = trace_id
    client: dict[str, str] = {}
    if ip := mask_ip(client_ip):
        client["ip"] = ip
    if user_agent:
        client["userAgent"] = user_agent[:MAX_USER_AGENT_LENGTH]
    if client:
        record["client"] = client
    return record
