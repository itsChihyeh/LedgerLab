// W1: signed charge.succeeded webhooks straight at /webhooks/stripe at a constant arrival rate.
// The Stripe-Signature is computed here the way Stripe does it: HMAC-SHA256 over "<t>.<body>".
import http from 'k6/http';
import crypto from 'k6/crypto';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const RATE = parseInt(__ENV.RATE, 10);
const DURATION = __ENV.DURATION || '120s';
const RUN = __ENV.RUN;
const SECRET = __ENV.WHSEC;
const TARGET = __ENV.APP + '/webhooks/stripe';

const accepted = new Counter('w1_accepted');
const rejected = new Counter('w1_not_2xx');

export const options = {
  scenarios: {
    webhooks: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(20, RATE),
      maxVUs: RATE * 10,
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: { 'http_req_duration{ep:webhook}': ['max>=0'] },
};

export default function () {
  const n = exec.scenario.iterationInTest;
  const now = Math.floor(Date.now() / 1000);
  const amount = 500 + (n % 19500);
  const event = {
    id: `evt_perf${RUN}${n}`,
    object: 'event',
    api_version: '2026-09-30.endive',
    created: now,
    livemode: false,
    type: 'charge.succeeded',
    data: {
      object: {
        id: `ch_perf${RUN}${n}`,
        object: 'charge',
        payment_intent: `pi_perf${RUN}${n}`,
        amount: amount,
        amount_captured: amount,
        currency: 'cad',
        captured: true,
        status: 'succeeded',
        livemode: false,
        created: now,
      },
    },
  };
  const body = JSON.stringify(event);
  const signature = crypto.hmac('sha256', SECRET, `${now}.${body}`, 'hex');
  const res = http.post(TARGET, body, {
    headers: { 'Content-Type': 'application/json', 'Stripe-Signature': `t=${now},v1=${signature}` },
    tags: { ep: 'webhook' },
  });
  if (res.status >= 200 && res.status < 300) accepted.add(1);
  else rejected.add(1);
}

export function handleSummary(data) {
  return { '/out/summary.json': JSON.stringify(data) };
}
