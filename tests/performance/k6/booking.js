// NFR-P2: booking p95 ≤ 800 ms at 50 RPS, 5xx error rate < 0.1%, with 20% of requests contending for the same slot (AC-03.1).
//   k6 run -e BASE_URL=... -e TOKEN=<token> -e PATIENTS=<number of fake patients> tests/performance/k6/booking.js
// Each iteration picks a free slot and books it with a new Idempotency-Key. 409 (slot just booked) and 422 (3 appointments already) are valid
// business outcomes and are not counted as errors; only 5xx and other codes are.
import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:18000';
const TOKEN = __ENV.TOKEN || '';
const SCENARIO = __ENV.SCENARIO || 'smoke';
const HOT_SLOT = __ENV.HOT_SLOT || ''; // shared slot used to create contention (20% of requests)

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
  check(res, { 'booking returns a valid business result': (r) => [200, 201, 409, 422].includes(r.status) });
}
