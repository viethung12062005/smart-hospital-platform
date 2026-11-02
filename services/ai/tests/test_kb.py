"""US-052: kho tri thức (AI-01..AI-05)."""

import io
import time
from pathlib import Path
from typing import Any

import pytest

from shp_ai.deps import Deps
from shp_ai.kb import parsers
from shp_ai.kb.chunker import chunk
from shp_ai.kb.index import IndexedChunk, MemoryIndex, OpenSearchIndex, Scope
from shp_ai.kb.service import KnowledgeBase, NewDocument
from shp_ai.tokens import count
from shp_platform.errors import ShpError

from .conftest import ADMIN, DOCTOR, NURSE, PATIENT, bearer

DATA = Path(__file__).parent / "data"
MD = (DATA / "tang-huyet-ap.md").read_bytes()


def minimal_pdf(lines: list[str]) -> bytes:
    """PDF một trang có lớp chữ (Helvetica, ASCII) cho test parser."""
    text = "BT /F1 12 Tf 50 750 Td " + " ".join(f"({ln}) Tj 0 -16 Td" for ln in lines) + " ET"
    objs = [
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R "
        "/Resources << /Font << /F1 5 0 R >> >> >>",
        f"<< /Length {len(text)} >>\nstream\n{text}\nendstream",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    out, offsets = b"%PDF-1.4\n", []
    for i, o in enumerate(objs, start=1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n{o}\nendobj\n".encode()
    xref = len(out)
    out += f"xref\n0 {len(objs) + 1}\n0000000000 65535 f \n".encode()
    out += "".join(f"{o:010d} 00000 n \n" for o in offsets).encode()
    out += f"trailer\n<< /Size {len(objs) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF".encode()
    return out


def docx_bytes() -> bytes:
    from docx import Document

    d = Document()
    d.add_heading("Phác đồ đái tháo đường", level=1)
    d.add_heading("2. Chẩn đoán", level=2)
    d.add_paragraph("Glucose huyết tương lúc đói từ 7,0 mmol/L trở lên.")
    buf = io.BytesIO()
    d.save(buf)
    return buf.getvalue()


def test_parsers_keep_sections_and_pages() -> None:
    md = parsers.parse(MD, parsers.MARKDOWN)
    assert any(b.section.endswith("4.2 Chỉ định chuyển tuyến") and "180/110" in b.text for b in md)
    dx = parsers.parse(docx_bytes(), parsers.DOCX)
    assert dx[-1].section == "Phác đồ đái tháo đường > 2. Chẩn đoán" and "7,0 mmol/L" in dx[-1].text
    pdf = parsers.parse(
        minimal_pdf(["1. Muc tieu", "Kiem soat huyet ap.", "2. Chuyen tuyen", "Khi HA 180/110."]), parsers.PDF
    )
    assert [b.section for b in pdf] == ["1. Muc tieu", "2. Chuyen tuyen"] and pdf[0].page == 1
    with pytest.raises(parsers.ParseError):
        parsers.parse(minimal_pdf([]), parsers.PDF)  # không có lớp chữ


def test_chunker_respects_sections_size_and_overlap() -> None:
    long = " ".join(f"câu{i} về điều trị." for i in range(700))
    blocks = [parsers.Block("A", 1, long), parsers.Block("B", 2, "ngắn gọn")]
    chunks = chunk(blocks)
    assert all(c.tokens <= 650 for c in chunks)
    assert {c.section for c in chunks} == {"A", "B"} and chunks[-1].text == "ngắn gọn"
    a = [c for c in chunks if c.section == "A"]
    assert len(a) >= 3 and a[0].text.split()[-5:][0] in a[1].text  # overlap 80 token
    assert count("Huyết áp 180/110") == 5  # Huyết, áp, 180, /, 110


def _kb(deps: Deps) -> KnowledgeBase:
    return deps.kb


def test_lifecycle_versioning_without_gap_and_retire(make_deps: Any) -> None:
    deps = make_deps()
    kb = _kb(deps)
    doc = kb.upload(NewDocument("Phác đồ THA", "nội bộ", "cardiology", ("doctor",)), MD, "text/markdown", "admin-1")
    assert doc["status"] == "draft"
    with pytest.raises(ShpError):
        kb.approve(doc["_id"], "dr-derm", ("dermatology",), False)  # sai khoa
    kb.approve(doc["_id"], "dr-cardio", ("cardiology",), False)
    out = kb.index_document(doc["_id"])
    assert out["status"] == "indexed" and out["chunkCount"] >= 3 and out["indexedVersion"] == 1
    index: MemoryIndex = deps.index
    v1 = {c.id for c in index.chunks.values()}
    kb.replace(doc["_id"], MD.replace(b"180/110", b"170/105"), "text/markdown", "admin-1")
    assert {c.id for c in index.chunks.values()} == v1  # version cũ vẫn phục vụ khi chờ duyệt
    kb.approve(doc["_id"], "admin-1", (), True)
    kb.index_document(doc["_id"])
    assert all(c.document_version == 2 for c in index.chunks.values())
    assert any("170/105" in c.content for c in index.chunks.values())
    kb.retire(doc["_id"], "admin-1")
    assert not index.chunks and kb.get(doc["_id"])["status"] == "retired"


def test_index_failure_retries_then_keeps_approved(make_deps: Any) -> None:
    deps = make_deps()

    class Flaky(MemoryIndex):
        calls = 0

        def upsert(self, chunks: Any) -> None:
            Flaky.calls += 1
            raise ConnectionError("search down")

    deps.index = Flaky()
    kb = deps.kb
    doc = kb.upload(NewDocument("X", "nội bộ", "cardiology", ("doctor",)), MD, "text/markdown", "a")
    kb.approve(doc["_id"], "a", (), True)
    out = kb.index_document(doc["_id"])
    assert Flaky.calls == 3 and out["status"] == "approved" and out["indexError"] == "ConnectionError"


def test_upload_rejects_bad_types_and_spoofed_content(make_deps: Any) -> None:
    kb = make_deps().kb
    meta = NewDocument("X", "nội bộ", "cardiology", ("doctor",))
    for data, ctype in [(b"MZ\x90\x00binary", "application/pdf"), (b"\x00\x01", "text/markdown")]:
        with pytest.raises(ShpError) as e:
            kb.upload(meta, data, ctype, "a")
        assert e.value.info.code == "invalid-file-type"
    with pytest.raises(ShpError):
        kb.upload(NewDocument("X", "n", "cardiology", ("patient",)), MD, "text/markdown", "a")


def test_security_trimming_happens_in_query() -> None:
    idx = MemoryIndex()

    def c(i: str, dep: str, roles: tuple[str, ...]) -> IndexedChunk:
        return IndexedChunk(i, i, 1, "t", "s", 1, dep, roles, "huyết áp chuyển tuyến", (1.0,))

    idx.upsert(
        [
            c("a", "cardiology", ("doctor",)),
            c("b", "dermatology", ("doctor",)),
            c("c", "hospital-wide", ("doctor", "nurse")),
            c("d", "cardiology", ("nurse",)),
        ]
    )
    doctor = Scope("doctor", ("cardiology",))
    assert {h.chunk.id for h in idx.keyword("huyết áp", doctor, 10)} == {"a", "c"}
    assert {h.chunk.id for h in idx.vector([1.0], Scope("nurse", ("cardiology",)), 10)} == {"c", "d"}


def test_api_permissions_and_audit(api: Any) -> None:
    tc, deps = api()
    with tc:
        form = {"title": "Phác đồ THA", "source": "nội bộ", "department": "cardiology", "allowedRoles": ["doctor"]}
        files = {"file": ("tha.md", MD, "text/markdown")}
        assert tc.post("/api/v1/ai/kb/documents", data=form, files=files, headers=bearer(**DOCTOR)).status_code == 403
        r = tc.post("/api/v1/ai/kb/documents", data=form, files=files, headers=bearer(**ADMIN))
        assert r.status_code == 201, r.text
        doc_id = r.json()["id"]
        assert tc.post(f"/api/v1/ai/kb/documents/{doc_id}/approve", headers=bearer(**NURSE)).status_code == 403
        r = tc.post(f"/api/v1/ai/kb/documents/{doc_id}/approve", headers=bearer(**DOCTOR))
        assert r.status_code == 200 and r.json()["status"] == "approved"
        for _ in range(50):
            if deps.kb.get(doc_id)["status"] == "indexed":
                break
            time.sleep(0.1)
        listed = tc.get("/api/v1/ai/kb/documents", headers=bearer(**DOCTOR)).json()["items"]
        assert listed[0]["status"] == "indexed"
        assert tc.get("/api/v1/ai/kb/documents", headers=bearer(**PATIENT)).status_code == 403
        audits = [o["payload"]["data"] for o in deps.db.outbox.find({"eventType": "shp.audit.recorded.v1"})]
        assert [a["action"] for a in audits] == ["create", "update"]
        assert all("Phác đồ" not in str(a) for a in audits)


@pytest.mark.skipif("not __import__('shp_platform.testing', fromlist=['x']).docker_available()")
def test_opensearch_index_roundtrip() -> None:
    from testcontainers.core.container import DockerContainer

    c = (
        DockerContainer("opensearchproject/opensearch:2.18.0")
        .with_env("discovery.type", "single-node")
        .with_env("DISABLE_SECURITY_PLUGIN", "true")
        .with_env("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
        .with_exposed_ports(9200)
    )
    c.start()
    try:
        import httpx

        url = f"http://{c.get_container_host_ip()}:{c.get_exposed_port(9200)}"
        for _ in range(120):
            try:
                if httpx.get(url, timeout=2).status_code == 200:
                    break
            except httpx.HTTPError:
                pass
            time.sleep(1)
        idx = OpenSearchIndex(url, "kb-test", dimensions=4)
        idx.ensure()
        idx.ensure()
        chunks = [
            IndexedChunk(
                "d1:1:0",
                "d1",
                1,
                "THA",
                "4.2",
                1,
                "cardiology",
                ("doctor",),
                "chuyển tuyến 180/110",
                (1.0, 0.0, 0.0, 0.0),
            ),
            IndexedChunk(
                "d2:1:0",
                "d2",
                1,
                "Da",
                "1",
                1,
                "dermatology",
                ("doctor",),
                "chuyển tuyến da liễu",
                (0.9, 0.1, 0.0, 0.0),
            ),
        ]
        idx.upsert(chunks)
        scope = Scope("doctor", ("cardiology",))
        assert [h.chunk.id for h in idx.keyword("chuyển tuyến", scope, 5)] == ["d1:1:0"]
        assert [h.chunk.id for h in idx.vector([1.0, 0, 0, 0], scope, 5)] == ["d1:1:0"]
        idx.delete_document("d1")
        assert idx.keyword("chuyển tuyến", scope, 5) == []
    finally:
        c.stop()
