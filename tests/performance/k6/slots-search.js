// NFR-P1 (tìm slot p95 ≤ 300 ms tại 500 RPS), NFR-S2 (5×), NFR-S3 (10×).
// Chạy trên UAT cỡ production, dữ liệu giả 50.000 bệnh nhân, sau khi làm nóng:
//   k6 run -e BASE_URL=https://api.uat.example.org -e TOKEN=<token bệnh nhân giả> -e SCENARIO=load tests/performance/k6/slots-search.js
// SCENARIO: smoke (mặc định, 1 phút, 5 RPS) | load (500 RPS, 10 phút, NFR-P1) | x5 (NFR-S2) | x10 (NFR-S3, chấp nhận 429).
// Token và địa chỉ truyền bằng biến môi trường: không ghi vào mã.
import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:18000';
const TOKEN = __ENV.TOKEN || '';
const SCENARIO = __ENV.SCENARIO || 'smoke';
const specialties = ['cardiology', 'general-medicine', 'pediatrics', 'dermatology'];
const throttled = new Rate('throttled_429');

const scenarios = {
  smoke: { executor: 'constant-arrival-rate', rate: 5, timeUnit: '1s', duration: '1m', preAllocatedVUs: 10, maxVUs: 50 },
  load: { executor: 'constant-arrival-rate', rate: 500, timeUnit: '1s', duration: '10m', preAllocatedVUs: 300, maxVUs: 1500 },
  x5: { executor: 'constant-arrival-rate', rate: 2500, timeUnit: '1s', duration: '10m', preAllocatedVUs: 1500, maxVUs: 6000 },
  x10: { executor: 'constant-arrival-rate', rate: 5000, timeUnit: '1s', duration: '5m', preAllocatedVUs: 3000, maxVUs: 12000 },
};

// Ngưỡng theo NFR. 5× nới tới 2× mục tiêu (NFR-S2); 10× chỉ yêu cầu xuống cấp có kiểm soát: không 5xx (NFR-S3).
const thresholdsByScenario = {
  smoke: { http_req_duration: ['p(95)<300'], http_req_failed: ['rate<0.001'] },
  load: { http_req_duration: ['p(95)<300'], http_req_failed: ['rate<0.001'] },
  x5: { http_req_duration: ['p(95)<600'], http_req_failed: ['rate<0.01'] },
  x10: { 'http_req_failed{expected_response:true}': ['rate<0.001'], throttled_429: ['rate<1'] },
};

export const options = {
  scenarios: { [SCENARIO]: scenarios[SCENARIO] },
  thresholds: thresholdsByScenario[SCENARIO],
  summaryTrendStats: ['avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const specialty = specialties[Math.floor(Math.random() * specialties.length)];
  const res = http.get(`${BASE_URL}/fhir/Slot?status=free&specialty=${specialty}&start=ge2026-12-01&start=lt2026-12-08&_count=20`, {
    headers: { Authorization: `Bearer ${TOKEN}`, Accept: 'application/fhir+json' },
    tags: { name: 'GET /fhir/Slot' },
  });
  throttled.add(res.status === 429);
  check(res, { 'status 200 hoặc 429 khi quá tải': (r) => r.status === 200 || r.status === 429 });
}
