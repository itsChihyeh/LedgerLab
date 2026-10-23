#!/bin/sh
# T16: AC-2.3, AC-5.1/5.4/5.8/5.17 across real process boundaries.
set -eu
cd "$(dirname "$0")/.."

command -v docker >/dev/null
if command -v python >/dev/null && python -c 'import sys; assert sys.version_info >= (3, 9)' 2>/dev/null; then
  PYTHON=python
else
  PYTHON=python3
  "$PYTHON" -c 'import sys; assert sys.version_info >= (3, 9)'
fi
docker info >/dev/null

# Explicitly load ONLY public smoke values, overriding inherited credentials. --env-file
# prevents Compose's implicit .env loading; -f/-p prevent ambient project selection.
set -a
. ./compose.smoke.env
set +a
unset COMPOSE_FILE COMPOSE_PROFILES COMPOSE_ENV_FILES
export COMPOSE_DISABLE_ENV_FILE=1
project="ledgerlab-smoke-$("$PYTHON" -c 'import uuid; print(uuid.uuid4().hex[:12])')"
compose() {
  docker compose --env-file compose.smoke.env -f compose.yaml -p "$project" --profile smoke "$@"
}
mkdir -p target/smoke-p2
cleanup() {
  result=$?
  trap - EXIT HUP INT TERM
  compose logs --no-color >target/smoke-p2/compose.log 2>&1 || true
  if [ "$result" -ne 0 ]; then
    echo "Smoke failed; last service logs (full log: target/smoke-p2/compose.log):" >&2
    tail -n 100 target/smoke-p2/compose.log >&2
  fi
  compose down -v --remove-orphans || result=1
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' HUP TERM

echo 'Building the app and simulator jars...'
./mvnw -B -DskipTests package
./mvnw -B -f simulator/pom.xml -DskipTests package
echo "Starting disposable project $project..."
compose up -d --build --wait --wait-timeout 180
app_address=$(compose port app 8080)
sim_address=$(compose port simulator 8081)

"$PYTHON" -u - "$app_address" "$sim_address" <<'PY'
import json
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, ProxyHandler, build_opener

APP, SIM = ('http://' + address.strip() for address in sys.argv[1:])
OUT = Path('target/smoke-p2')
http = build_opener(ProxyHandler({}))


def request(base, path, method='GET', data=None, csv=False):
    body = data if isinstance(data, bytes) else (
        json.dumps(data).encode() if data is not None else None)
    headers = {'Content-Type': 'text/csv' if csv else 'application/json'}
    try:
        with http.open(Request(base + path, body, headers, method=method), timeout=20) as response:
            raw = response.read()
            return raw if csv and method == 'GET' else json.loads(raw)
    except HTTPError as error:
        raise RuntimeError(f'{method} {path}: HTTP {error.code}: {error.read().decode()}') from error


def save(name, value):
    (OUT / (name + '.json')).write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')
    return value


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def wait_for(label, fetch, ready, timeout=90):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            last = fetch()
            if ready(last):
                return last
        except (RuntimeError, URLError) as error:
            last = str(error)
        # Bounded condition polling, never a timing assumption about worker completion.
        time.sleep(0.5)
    raise AssertionError(f'Timed out waiting for {label}: {last}')


def instant(value):
    return datetime.fromisoformat(value.replace('Z', '+00:00'))


def advance_to(target):
    now = instant(request(SIM, '/sim/clock')['now'])
    seconds = int((instant(target) - now).total_seconds())
    check(seconds >= 0, 'Simulator clock moved beyond the scenario')
    request(SIM, '/sim/clock/advance', 'POST', {'seconds': seconds})


# Same actions and amounts as sample-a-1-3; historical dates let the real app
# Clock enforce cutoff <= evidence <= now - 60s without a production test hook.
cutoff = '2026-10-22T04:00:00Z'  # Toronto midnight (DST).
overview = request(APP, '/api/overview')
check(instant(overview['asOf']['at']) >= instant(cutoff), 'Host clock must be after 2026-10-22')
check(all(a['balance'] == 0 for a in overview['accounts']), 'Smoke database must be empty')

plan = {'rules': [{'event_type': 'charge.succeeded', 'action': 'DUPLICATE', 'copies': 2}]}
check(request(SIM, '/sim/relay/plan', 'PUT', plan) == plan, 'DUPLICATE plan was not installed')
charge = save('charge', request(SIM, '/sim/charges', 'POST', {'amount': 10000}))
ch = charge['charge']['id']
payment_path = '/api/payments/' + ch
captured = wait_for('capture', lambda: request(APP, payment_path), lambda p: p['status'] == 'CAPTURED')
check(len(captured['postings']) == 2, 'Duplicate charge must still have only 2 postings')
deliveries = [d for d in captured['deliveries'] if d['eventId'] == charge['event_id']]
check(sorted(d['outcome'] for d in deliveries) == ['DUPLICATE', 'NEW'], 'Expected NEW + DUPLICATE')
request(SIM, '/sim/relay/plan', 'PUT', {'rules': []})
print('PASS AC-2.3: relay DUPLICATE produced 2 deliveries, 1 capture, 2 postings')

payout = save('payout', request(SIM, '/sim/payouts', 'POST', {}))
po = payout['payout']['id']
wait_for('announcement', lambda: request(APP, '/api/batches/' + po),
         lambda b: b['batch']['status'] == 'ANNOUNCED')
check(len(request(APP, payment_path)['postings']) == 2, 'Announcement must not settle')
reconciled = request(SIM, '/sim/payouts/' + po + '/reconcile', 'POST', {})
settled = wait_for('settlement', lambda: request(APP, payment_path), lambda p: p['status'] == 'SETTLED')
check(len(settled['postings']) == 7, 'Settlement must add exactly 5 postings')
overview = request(APP, '/api/overview')
check((overview['inTransit'], overview['bankCash'], overview['merchantAvailable']) == (9680, 0, 9680),
      'Wrong balances before the bank statement')
print('PASS AC-5.1/5.17: ANNOUNCED -> CONFIRMED, 5 settlement postings, inTransit=9680, cash=0')

request(SIM, '/sim/bank/plan', 'PUT', {'payout_ref': po, 'scenario': 'sample-a-1-3', 'fault': 'NONE'})
paid = request(SIM, '/sim/payouts/' + po + '/paid', 'POST', {})
wait_for('paid webhook', lambda: request(APP, payment_path),
         lambda p: any(d['eventId'] == paid['event_id'] and d['status'] == 'PROCESSED' for d in p['deliveries']))
check(request(APP, '/api/overview')['bankCash'] == 0, 'payout.paid must not book cash')

# Prove all four fake Stripe events went through the relay, including paid.
for event in (charge, payout, reconciled, paid):
    delivery = request(SIM, '/sim/events/' + event['event_id'])['deliveries']
    check(len(delivery) == 1 and delivery[0]['target_url'] == 'http://localhost:8081/relay/stripe'
          and delivery[0]['http_status'] == 200, 'Webhook bypassed relay or failed')

advance_to('2026-10-20T08:00:00Z')
snapshot = save('processor-snapshot', request(APP, '/api/recon/processor-snapshots', 'POST',
                                             {'businessDate': '2026-10-19'}))
check(snapshot['status'] == 'ACCEPTED', f'Processor snapshot rejected: {snapshot}')
advance_to('2026-10-21T12:00:00Z')
# October 20 is the next processing day (unlike the source sample's Nov 11 holiday).
statement = request(SIM, '/sim/bank/statements/2026-10-20', csv=True)
(OUT / 'bank.csv').write_bytes(statement)
imported = save('bank-import', request(APP, '/api/bank-statements', 'POST', statement, csv=True))
check(imported['status'] == 'ACCEPTED', f'Bank statement rejected: {imported}')
advance_to('2026-10-22T13:02:00Z')

before = save('payment', request(APP, payment_path))
overview = save('overview', request(APP, '/api/overview'))
check(before['status'] == 'SETTLED' and len(before['postings']) == 9, 'Expected SETTLED with 9 postings')
check(before['batch']['status'] == 'BANKED', 'Expected BANKED batch')
check((overview['inTransit'], overview['bankCash'], overview['merchantAvailable']) == (0, 9680, 9680),
      'Final overview differs from sample A step 3')
print('PASS AC-5.4: SETTLED, BANKED, 9 postings; inTransit=0, bankCash=9680, merchantAvailable=9680')

# The observation follows all writes. Round UP to the next whole second so
# PostgreSQL timestamp precision cannot change the persisted report. Wait until
# the app Clock admits that exact boundary with the unchanged evidence grace.
evidence = (instant(overview['asOf']['at']).replace(microsecond=0) + timedelta(seconds=1)).isoformat()
print('Waiting for the app Clock to satisfy the default 60s evidence grace...')
wait_for('evidence grace', lambda: request(APP, '/api/overview'),
         lambda o: instant(o['asOf']['at']) >= instant(evidence) + timedelta(seconds=60))
report = save('reconciliation', request(APP, '/api/recon/runs', 'POST',
    {'kind': 'ALL', 'cutoffAt': cutoff, 'evidenceAsOf': evidence, 'dryRun': False}))
check(len(report['runIds']) == 3 and len(set(report['runIds'])) == 3, 'Expected 3 persisted runs')
check({r['kind'] for r in report['runs']} == {'PROCESSOR', 'BANK', 'AGING'}, 'Missing reconciliation kind')
for run in report['runs']:
    check(run['status'] == 'FINISHED' and run['findings'] == [], f'Reconciliation failed: {run}')
    stored = save('run-' + run['kind'].lower(), request(APP, '/api/recon/runs/' + run['id']))
    check(stored == run, f'Persisted {run["kind"]} report differs: {stored}')
    if run['kind'] in ('PROCESSOR', 'BANK'):
        file_id = snapshot['fileId'] if run['kind'] == 'PROCESSOR' else imported['fileId']
        check(file_id in run['inputHashes'], 'Reconciliation must include the imported evidence')
issues = save('issues', request(APP, '/api/recon/issues'))
check(issues['issues'] == [], f'Unexpected issues: {issues}')
check(request(APP, payment_path)['postings'] == before['postings'], 'Reconciliation changed postings')
check(request(APP, '/api/overview')['accounts'] == overview['accounts'], 'Reconciliation changed balances')
print('PASS AC-5.8: PROCESSOR/BANK/AGING FINISHED, 0 findings, 0 issues; ledger unchanged')
print('PASS T16 sample-a-1-3: fake Stripe -> relay -> app -> bank import -> reconciliation')
PY
echo 'Smoke passed. Evidence: target/smoke-p2/ (services and volumes are removed on exit).'
