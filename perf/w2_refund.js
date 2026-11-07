// W2: refund acceptance against settled payments of one merchant. Every accepted refund takes the merchant funds
// lock (account 2102, ADR-0004), so concurrency should not raise throughput. VUS=1 gives the serial reference.
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const PAYMENTS = JSON.parse(open('/out/payments.json'));
const VUS = parseInt(__ENV.VUS, 10);
const ITERATIONS = parseInt(__ENV.ITERATIONS, 10);
const AMOUNT = parseInt(__ENV.AMOUNT, 10);
const PHASE = __ENV.PHASE;

const created = new Counter('w2_created_201');
const other = new Counter('w2_not_201');

export const options = {
  scenarios: {
    refunds: { executor: 'shared-iterations', vus: VUS, iterations: ITERATIONS, maxDuration: '15m' },
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: { 'http_req_duration{ep:refund}': ['max>=0'] },
};

export default function () {
  const n = exec.scenario.iterationInTest;
  const key = `perf-${PHASE}-${n}`;
  const res = http.post(
    __ENV.APP + '/api/refunds',
    JSON.stringify({ payment_ref: PAYMENTS[n % PAYMENTS.length], merchant_refund_ref: key, amount: AMOUNT }),
    { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key }, tags: { ep: 'refund' } },
  );
  if (res.status === 201) created.add(1);
  else other.add(1);
}

export function handleSummary(data) {
  return { '/out/summary.json': JSON.stringify(data) };
}
