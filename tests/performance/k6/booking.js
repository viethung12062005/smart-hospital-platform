// NFR-P2: đặt lịch p95 ≤ 800 ms tại 50 RPS, lỗi 5xx < 0,1%, kèm 20% yêu cầu tranh chấp cùng một slot (AC-03.1).
//   k6 run -e BASE_URL=... -e TOKEN=<token> -e PATIENTS=<số bệnh nhân giả> tests/performance/k6/booking.js
// Mỗi lần lặp lấy một slot trống rồi đặt bằng Idempotency-Key mới. 409 (slot vừa bị đặt) và 422 (đủ 3 lịch) là kết quả
// nghiệp vụ hợp lệ, không tính là lỗi; chỉ 5xx và các mã khác mới tính.
import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:18000';
const TOKEN = __ENV.TOKEN || '';
const SCENARIO = __ENV.SCENARIO || 'smoke';
const HOT_SLOT = __ENV.HOT_SLOT || ''; // slot dùng chung để tạo tranh chấp (20% yêu cầu)

http.setResponseCallback(http.expectedStatuses(200, 201, 409, 422));

const scenarios = {
  smoke: { executor: 'constant-arrival-rate', rate: 2, timeUnit: '1s', duration: '1m', preAllocatedVUs: 10, maxVUs: 40 },
  load: { executor: 'constant-arrival-rate', rate: 50, timeUnit: '1s', duration: '10m', preAllocatedVUs: 100, maxVUs: 400 },
};

export const options = {
  scenarios: { [SCENARIO]: scenarios[SCENARIO] },
  thresholds: { http_req_duration: ['p(95)<800'], http_req_failed: ['rate<0.001'] },
  summaryTrendStats: ['avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const auth = { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/fhir+json' };

export default function () {
  let slotId = HOT_SLOT;
  if (!slotId || Math.random() > 0.2) {
    const list = http.get(`${BASE_URL}/fhir/Slot?status=free&specialty=cardiology&_count=50`, { headers: auth, tags: { name: 'GET /fhir/Slot' } });
    const entries = (list.json('entry') || []).map((e) => e.resource.id);
    if (entries.length === 0) return;
    slotId = entries[Math.floor(Math.random() * entries.length)];
  }
  const body = JSON.stringify({
    resourceType: 'Parameters',
    parameter: [{ name: 'slot', valueReference: { reference: `Slot/${slotId}` } }],
  });
  const res = http.post(`${BASE_URL}/fhir/Appointment/$book`, body, {
    headers: { ...auth, 'Idempotency-Key': uuidv4() },
    tags: { name: 'POST $book' },
  });
  check(res, { 'đặt lịch cho kết quả nghiệp vụ hợp lệ': (r) => [200, 201, 409, 422].includes(r.status) });
}
