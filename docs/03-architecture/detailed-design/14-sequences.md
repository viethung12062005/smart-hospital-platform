# 14 — Sequence diagram các luồng chính

Mỗi sơ đồ ghi rõ chỗ **đồng bộ** (mũi tên liền), **bất đồng bộ** (mũi tên đứt), và điểm lỗi. Mã API (S11, C14...) tham chiếu
tài liệu 08–12; mã event (E1...) tham chiếu [13](13-events.md).

## 1. Đặt lịch (UC-03) — S11, E1

```mermaid
sequenceDiagram
    autonumber
    actor P as Bệnh nhân (web)
    participant GW as Gateway
    participant SCH as Scheduling
    participant PG as PostgreSQL
    participant R as Redis
    participant BUS as Bus
    participant NTF as Notification
    participant ML as ML
    participant CLN as Clinical

    P->>GW: GET /fhir/Slot?specialty=cardiology&start=ge...
    GW->>SCH: (JWT hợp lệ, rate limit)
    SCH->>R: GET cache khóa truy vấn
    alt cache miss
        SCH->>PG: SELECT slot trống (ix_slot_free_by_specialty)
        SCH->>R: SET TTL 5s
    end
    SCH-->>P: Bundle slot trống

    P->>GW: POST /fhir/Appointment/$book (Idempotency-Key)
    GW->>SCH: forward
    SCH->>CLN: identity(sub) → fhirUser (cache 60s)
    SCH->>PG: BEGIN, giành idempotency key, advisory lock bệnh nhân
    SCH->>PG: đếm lịch tương lai (BR-03), UPDATE slot free→busy (có điều kiện)
    alt slot đã bị đặt
        SCH->>PG: ROLLBACK
        SCH-->>P: 409 slot-unavailable + 3 slot gợi ý
    else thành công
        SCH->>PG: INSERT appointment, history, outbox(E1), outbox(E11), COMMIT
        SCH-->>P: 201 Appointment (booked)
        SCH->>R: xóa cache slot của bác sĩ/ngày
    end

    Note over SCH,BUS: Relay (mỗi 1s) thêm ageBand rồi phát E1
    SCH--)BUS: E1 appointment-booked
    BUS--)NTF: E1 → tạo 3 nhắc (xác nhận, T-24h, T-2h)
    BUS--)ML: E1 → chấm điểm no-show
    BUS--)CLN: E1 → care_relationships
    ML--)BUS: E10 no-show-scored
    BUS--)NTF: E10 → thêm nhắc T-6h nếu rủi ro cao
```
**Điểm lỗi:** Redis chết → bỏ qua cache, đọc thẳng DB (chậm hơn, đúng). Clinical chậm → identity dùng cache; cache rỗng và clinical
chết → 503 (không đặt lịch khi không xác định được người dùng). Bus chết → E1 nằm trong outbox, phát sau; lịch hẹn vẫn đặt thành công.

## 2. Nhắc lịch và xử lý nhà cung cấp lỗi (UC-07)

```mermaid
sequenceDiagram
    participant W as Notification worker
    participant DB as PostgreSQL (notification)
    participant CLN as Clinical
    participant PUSH as Push provider
    participant SMS as SMS provider
    participant OBS as Cảnh báo

    loop mỗi 10 giây
        W->>DB: lấy reminder đến hạn (FOR UPDATE SKIP LOCKED) → sending
    end
    W->>CLN: GET /internal/v1/patients/{id}/contact (audit)
    W->>PUSH: gửi (kênh ưu tiên 1)
    alt push thành công
        W->>DB: delivery_attempt accepted, reminder = sent
    else push lỗi tạm thời
        W->>SMS: gửi (kênh dự phòng)
        alt SMS thành công
            W->>DB: attempt accepted (channel=sms), reminder = sent
        else mọi kênh lỗi
            W->>DB: reminder = scheduled, due_at = now + backoff (1, 5, 15, 60 phút)
        end
    end
    Note over W,DB: Lần thứ 5 thất bại → dead-lettered
    W->>OBS: metric + cảnh báo DLQ
    Note over W,DB: Worker chết giữa chừng: locked_until quá hạn → dòng được thu hồi và gửi lại (idempotent nhờ attempt unique)
```

## 3. Check-in → lần khám → kết thúc (UC-04) — S16, C11, C14, E4–E6

```mermaid
sequenceDiagram
    actor N as Điều dưỡng
    actor D as Bác sĩ
    participant SCH as Scheduling
    participant BUS as Bus
    participant CLN as Clinical
    participant ML as ML

    N->>SCH: POST /Appointment/{id}/$check-in (If-Match)
    SCH->>SCH: booked → arrived (cửa sổ -60'/+30')
    SCH--)BUS: E4 checked-in
    BUS--)CLN: E4 → tạo Encounter(arrived), idempotent theo appointmentId
    N->>CLN: POST /Observation (sinh hiệu, gắn Encounter)
    D->>CLN: POST /Encounter/{id}/$start (kiểm Care)
    D->>CLN: POST /Condition (ICD-10), PUT /Encounter (bệnh sử)
    D->>CLN: POST /Encounter/{id}/$finish (yêu cầu ≥1 chẩn đoán)
    CLN--)BUS: E6 encounter-finished
    BUS--)SCH: E6 → Appointment arrived → fulfilled
    SCH--)BUS: E5 status-changed(fulfilled)
    BUS--)ML: E5 → outcome=fulfilled, cập nhật attendance_stats
```

## 4. Tải tài liệu và trích xuất AI (UC-05, UC-13) — D1, D2, E8, AI6

```mermaid
sequenceDiagram
    actor N as Điều dưỡng
    participant DOC as Document
    participant BLOB as Blob Storage
    participant AV as Trình quét mã độc
    participant BUS as Bus
    participant AI as AI service
    participant CLN as Clinical
    actor D as Bác sĩ

    N->>DOC: POST /documents/uploads (kiểm loại, size, Care)
    DOC-->>N: SAS ghi 10 phút
    N->>BLOB: PUT file (trực tiếp)
    N->>DOC: POST /uploads/{id}/complete
    DOC->>BLOB: kiểm size + SHA-256 + magic bytes
    DOC-->>N: 202 scanning
    BLOB->>AV: quét khi blob tạo
    AV-->>DOC: kết quả
    alt nhiễm mã độc
        DOC->>BLOB: chuyển sang quarantine, cảnh báo, KHÔNG phát E8
    else sạch
        DOC--)BUS: E8 document-uploaded
        BUS--)AI: E8 → extraction_job (queued)
        AI->>DOC: POST /internal/documents/{id}/read-url (60s)
        AI->>BLOB: đọc file → OCR → phân loại → trích trường → validate
        Note over AI: Lỗi bước nào → retry (1/5/15') → failed (DLQ). Upload không bị ảnh hưởng
        AI->>AI: status = needs-review
    end
    D->>AI: GET /extractions (hàng chờ) → xem trường + tài liệu gốc
    D->>AI: POST /extractions/{id}/review (accepted, có thể sửa)
    AI->>CLN: POST /Observation (bằng TOKEN của bác sĩ), derivedFrom=DocumentReference
```

## 5. Hỏi trợ lý tri thức với fallback (UC-11) — AI1

```mermaid
sequenceDiagram
    actor U as Bác sĩ
    participant AI as AI service
    participant SRCH as AI Search
    participant M1 as DeepSeek (chính)
    participant M2 as Gemini free tier (dự phòng 1)
    participant M3 as Ollama (dự phòng 2)
    participant AUD as Audit

    U->>AI: POST /assistant/ask
    AI->>AI: kiểm đầu vào (độ dài, mẫu injection)
    AI->>SRCH: hybrid (embedding local), filter role/khoa (security trimming)
    alt không có đoạn đạt ngưỡng
        AI-->>U: answered=false (không gọi LLM)
    else có ngữ cảnh
        AI->>M1: prompt đã redact PII (ngữ cảnh đánh dấu <document>)
        alt M1 timeout / 5xx / 429 / circuit mở
            AI->>M2: thử model kế (redact PII)
            alt M2 lỗi
                AI->>M3: thử model kế
            end
        end
        alt có câu trả lời
            AI->>AI: kiểm schema + trích dẫn khớp chunk + không có chẩn đoán
            AI-->>U: answer + citations (+ model, fallbackLevel)
        else mọi model lỗi
            AI-->>U: mode=retrieval-only (danh sách mục liên quan)
        end
    end
    AI--)AUD: E11 ai-query (model, token, chi phí, không có nội dung câu hỏi)
```

## 6. Truy cập khẩn cấp — break-glass (UC-10) — C20

```mermaid
sequenceDiagram
    actor D as Bác sĩ (không có Care)
    participant CLN as Clinical
    participant BUS as Bus
    participant AUD as Audit
    participant NTF as Notification
    actor DPO

    D->>CLN: GET /fhir/Patient/{id}
    CLN-->>D: 403 break-glass-required (+ audit denied)
    D->>CLN: POST /break-glass {patientId, reason ≥ 20 ký tự}
    CLN->>CLN: tạo phiên 60 phút (TTL)
    CLN--)BUS: E11 break-glass-start (ETREAT)
    CLN-->>D: 201 {expiresAt}
    BUS--)AUD: ghi WORM + Log Analytics
    AUD--)NTF: cảnh báo DPO (≤ 5 phút)
    NTF--)DPO: email + push
    D->>CLN: GET /fhir/Patient/{id}, /timeline (mỗi lần audit ETREAT)
    DPO->>AUD: GET /audit/break-glass → xem xét (justified / unjustified)
```
