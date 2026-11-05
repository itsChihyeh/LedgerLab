// W3: read-only queries at 50 concurrent users: the overview page and a payment's detail.
import http from 'k6/http';
import { check } from 'k6';

const PAYMENTS = JSON.parse(open('/out/payments.json'));

export const options = {
  scenarios: {
    readers: { executor: 'constant-vus', vus: parseInt(__ENV.VUS, 10), duration: __ENV.DURATION || '60s' },
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    'http_req_duration{ep:overview}': ['max>=0'],
    'http_req_duration{ep:payment}': ['max>=0'],
  },
};

export default function () {
  const overview = http.get(__ENV.APP + '/api/overview', { tags: { ep: 'overview' } });
  check(overview, { 'overview 200': (r) => r.status === 200 });
  const ref = PAYMENTS[Math.floor(Math.random() * PAYMENTS.length)];
  const detail = http.get(`${__ENV.APP}/api/payments/${ref}`, { tags: { ep: 'payment' } });
  check(detail, { 'payment 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
  return { '/out/summary.json': JSON.stringify(data) };
}
