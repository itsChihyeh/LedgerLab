#!/bin/sh
# T11: disposable cross-process payout smoke (AC-7.1/7.6/7.8/7.10/7.13/7.22).
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

# Only public synthetic credentials; never implicitly load .env or reuse a project.
set -a
. ./compose.smoke.env
set +a
unset COMPOSE_FILE COMPOSE_PROFILES COMPOSE_ENV_FILES
export COMPOSE_DISABLE_ENV_FILE=1
project="ledgerlab-smoke-p5-$("$PYTHON" -c 'import uuid; print(uuid.uuid4().hex[:12])')"
compose() {
  docker compose --env-file compose.smoke.env -f compose.yaml -f compose.smoke-p5.yaml \
    -p "$project" --profile smoke "$@"
}
mkdir -p target/smoke-p5
cleanup() {
  result=$?
  trap - EXIT HUP INT TERM
  compose logs --no-color >target/smoke-p5/compose.log 2>&1 || true
  if [ "$result" -ne 0 ]; then
    echo 'P5 smoke failed; full service log: target/smoke-p5/compose.log' >&2
    tail -n 100 target/smoke-p5/compose.log >&2
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

# Keep the scenario in this authorized script; reuse existing HTTP helpers only.
"$PYTHON" -u - "$app_address" "$sim_address" "$project" target/smoke-p5 <<'PY'
import csv
import io
import json
import os
import subprocess
import sys
from collections import Counter
from datetime import timedelta

sys.path.insert(0, 'scripts/smoke')
from common import APP, SIM, OUT, PROJECT, request, save, check, wait_for, instant, advance_to
from p3 import settle, pay


def snapshot(name):
    # One consistent readonly snapshot, exclusively through the granted views.
    views = {'accounts': 'ledger.v_account', 'entries': 'ledger.v_journal_entry',
             'postings': 'ledger.v_posting', 'payouts': 'core.v_payout',
             'payments': 'core.v_payment', 'refunds': 'core.v_refund',
             'batches': 'core.v_settlement_batch', 'bank': 'recon.v_bank_line'}
    fields = ','.join("'%s',(SELECT coalesce(jsonb_agg(v),'[]'::jsonb) FROM %s v)" % item
                      for item in views.items())
    sql = 'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SELECT jsonb_build_object(' + fields + '); COMMIT;'
    command = ['docker', 'compose', '--env-file', 'compose.smoke.env', '-f', 'compose.yaml',
               '-f', 'compose.smoke-p5.yaml', '-p', PROJECT, '--profile', 'smoke',
               'exec', '-T', '-e', 'PGPASSWORD=' + os.environ['LEDGERLAB_READONLY_PASSWORD'],
               'postgres', 'psql', '-h', '127.0.0.1', '-U', 'readonly', '-d', 'ledgerlab',
               '-XqAt', '-v', 'ON_ERROR_STOP=1', '-c', sql]
    state = json.loads(subprocess.check_output(command, text=True, timeout=30))
    # Canonical order also makes before/after reconciliation comparisons meaningful.
    state = {k: sorted(v, key=lambda r: json.dumps(r, sort_keys=True)) for k, v in state.items()}
    return save(name + '-snapshot', state)


def balances(name, cash=0, available=0, payout_transit=0, receivable=0, pending=0, transit=0):
    state = snapshot(name)
    expected = {1101: receivable, 1102: transit, 1103: cash, 2101: pending,
                2102: available, 2103: 0, 2104: payout_transit, 2105: 0}
    actual = {a['code']: a['balance'] for a in state['accounts']}
    check(actual == expected, f'{name}: expected {expected}, got {actual}')
    # INV-1/2/3/4/5/8: balance, sealed headers, independently recomputed accounts.
    recomputed = dict.fromkeys(expected, 0)
    for entry in state['entries']:
        rows = [p for p in state['postings'] if p['entry_id'] == entry['id']]
        check(len(rows) == entry['posting_count'] and len(rows) >= 2, 'Broken entry seal')
        debit = sum(p['amount'] for p in rows if p['side'] == 'D')
        credit = sum(p['amount'] for p in rows if p['side'] == 'C')
        check(debit == credit == entry['total_amount'], 'Entry does not balance')
        for p in rows:
            check(type(p['amount']) is int and p['amount'] > 0, 'Invalid posting amount')
            normal = 'D' if p['account_code'] < 2000 else 'C'
            recomputed[p['account_code']] += p['amount'] * (1 if p['side'] == normal else -1)
    check(recomputed == actual, 'Account balances differ from postings')
    check(sum(v for k, v in actual.items() if k < 2000) ==
          sum(v for k, v in actual.items() if k >= 2000), 'Assets differ from liabilities')
    check(all(actual[k] >= 0 for k in (1102, 1103, 2101, 2103, 2104, 2105)), 'Negative protected account')
    check(len({e['source_key'] for e in state['entries']}) == len(state['entries']), 'Duplicate source key')
    # INV-9/10/12, including UNKNOWN retaining its reservation.
    outstanding = ('RESERVED', 'SUBMITTED', 'UNKNOWN', 'ACCEPTED')
    check(actual[2104] == sum(p['amount'] for p in state['payouts'] if p['status'] in outstanding),
          'Payout obligations differ from 2104')
    check(actual[2101] == sum(p['amount'] for p in state['payments'] if p['status'] == 'CAPTURED'),
          'Captured obligations differ from 2101')
    check(actual[1102] == sum(b['net'] for b in state['batches'] if b['status'] == 'CONFIRMED'),
          'Batch obligations differ from 1102')
    check(state['refunds'] == [] and actual[2103] == 0, 'Unexpected refund obligations')
    current = {}
    for row in sorted(state['bank'], key=lambda r: (r['version'], r['import_order'])):
        current[row['bank_txn_id']] = row
    check(actual[2105] == sum(r['amount'] for r in current.values() if r['match_status'] == 'UNAPPLIED'),
          'Unapplied bank evidence differs from 2105')
    for p in state['payouts']:
        types = Counter(e['entry_type'] for e in state['entries']
                        if e['subject_type'] == 'payout' and e['subject_id'] == p['id'])
        wanted = Counter({'PAYOUT_RESERVED': 1})
        if p['status'] == 'FAILED':
            wanted['PAYOUT_FAILED'] = 1
        if p['status'] in ('PAID', 'RETURNED'):
            wanted['PAYOUT_PAID'] = 1
        if p['status'] == 'RETURNED':
            wanted['PAYOUT_RETURNED'] = 1
        check(types == wanted, f'Duplicate or premature payout effect: {p["id"]}: {types}')
    overview = save(name + '-overview', request(APP, '/api/overview'))
    check({a['code']: a['balance'] for a in overview['accounts']} == actual, 'Overview differs from snapshot')
    check(overview['payoutInTransit'] == payout_transit and
          overview['freeCash'] == cash - payout_transit, 'Wrong payout overview or free cash')
    print('PASS balances/invariants:', name, expected)
    return state


def plan(fault, parameter=0):
    save('plan-' + fault, request(SIM, '/sim/bank/plan', 'PUT',
         {'target_ref': '*', 'fault': fault, 'parameter': parameter, 'memo': 'ACCOUNT_CLOSED'}))


def detail(ref, status, done=True):
    return save(ref + '-' + status, wait_for(ref + ' ' + status,
        lambda: request(APP, '/api/payouts/' + ref),
        lambda d: d['payout']['status'] == status and len(d['commands']) == 1
        and d['commands'][0]['status'] == ('DONE' if done else 'UNKNOWN')))


def reserve(name, amount):
    before = request(APP, '/api/overview')
    check(amount <= before['merchantAvailable'] and amount <= before['freeCash'], 'INV-6 gate fixture')
    body = {'amount': amount, 'merchantPayoutRef': 'smoke-p5-' + name}
    headers = {'Idempotency-Key': 'smoke-p5-' + name}
    accepted = save(name + '-reserved', request(APP, '/api/payouts', 'POST', body,
                                              headers=headers, expected_status=201))
    check(accepted['status'] == 'RESERVED' and accepted['amount'] == amount and
          accepted['bankRequestId'] == accepted['payoutId'], 'Wrong original reservation')
    return accepted, body, headers


bank_files = []


def import_day(day, opening, credit, debit, closing):
    raw = request(SIM, '/sim/bank/statements/' + day, csv=True)
    (OUT / ('bank-' + day + '.csv')).write_bytes(raw)
    rows = list(csv.reader(io.StringIO(raw.decode('utf-8'))))
    lines, trailer = rows[1:-1], rows[-1]
    check([int(x) for x in trailer[1:]] == [len(lines), credit, debit, opening, closing],
          f'Bank trailer differs from hand calculation: {trailer}')
    check(opening + credit - debit == closing, 'INV-11 bank balance equation')
    check(sum(int(r[3]) for r in lines if r[2] == 'CREDIT' and r[5] == 'BOOKED') == credit and
          sum(int(r[3]) for r in lines if r[2] == 'DEBIT' and r[5] == 'BOOKED') == debit,
          'INV-11 bank totals differ from rows')
    imported = save('import-' + day, request(APP, '/api/bank-statements', 'POST', raw, csv=True))
    check(imported['status'] == 'ACCEPTED', f'Bank import rejected: {imported}')
    bank_files.append(imported['fileId'])
    # AC-7.13: replay the identical bank evidence; no extra posting or transition.
    before = snapshot('before-reimport-' + day)
    replay = request(APP, '/api/bank-statements', 'POST', raw, csv=True)
    check(replay['fileId'] == imported['fileId'], 'Repeated file changed identity')
    check(snapshot('after-reimport-' + day) == before, 'Repeated bank evidence changed business or ledger')
    return lines


def paid(ref, amount, returned=False):
    d = detail(ref, 'RETURNED' if returned else 'PAID')
    check([h['toStatus'] for h in d['history']][-1] == d['payout']['status'], 'Missing terminal history')
    expected = [('DEBIT', 'PAYOUT_PAID', 'bt_' + ref + '_1')]
    if returned:
        expected.append(('CREDIT', 'PAYOUT_RETURNED', 'bt_' + ref + '_R'))
        check(d['payout']['returnReason'] == 'ACCOUNT_CLOSED', 'Wrong return reason')
    check(sorted((r['direction'], r['matchStatus'], r['bankTxnId']) for r in d['bankLines']) == sorted(expected),
          'Payout bank evidence is not matched exactly once')
    check(all(r['matchedPayoutId'] == ref and r['amount'] == amount for r in d['bankLines']),
          'Wrong payout bank identity or amount')
    shapes = {'PAYOUT_RESERVED': [(2102, 'D'), (2104, 'C')],
              'PAYOUT_PAID': [(2104, 'D'), (1103, 'C')],
              'PAYOUT_RETURNED': [(1103, 'D'), (2102, 'C')]}
    for e in d['entries']:
        check(e['reversesEntryId'] is None and e['correctionId'] is None, 'Business payout became a correction')
        actual = sorted((p['accountCode'], p['side'], p['amount']) for p in d['postings'] if p['entryId'] == e['id'])
        check(actual == sorted((code, side, amount) for code, side in shapes[e['entryType']]), 'Wrong payout postings')
    return d


# S3 funding: 10000 charge - 320 fee = 9680 cash and merchant available.
cutoff = '2026-10-27T04:00:00Z'
check(instant(request(APP, '/api/overview')['asOf']['at']) >= instant(cutoff),
      'P5 smoke requires host clock at or after 2026-10-27T04:00:00Z')
balances('empty')
charge = save('funding-charge', request(SIM, '/sim/charges', 'POST', {'amount': 10000}))
wait_for('capture', lambda: request(APP, '/api/payments/' + charge['charge']['id']),
         lambda p: p['status'] == 'CAPTURED')
batch, _ = settle('funding', 9680)
pay(batch)
balances('settled', transit=9680, available=9680)
advance_to('2026-10-20T12:00:00Z')
import_day('2026-10-20', 0, 9680, 0, 9680)
balances('funded', cash=9680, available=9680)

plan('NONE')
normal, _, _ = reserve('normal', 2000)
n = normal['payoutId']
detail(n, 'ACCEPTED')
balances('normal-accepted-no-cash-movement', cash=9680, available=7680, payout_transit=2000)
advance_to('2026-10-21T12:00:00Z')
import_day('2026-10-21', 9680, 0, 2000, 7680)
paid(n, 2000)
balances('normal-paid', cash=7680, available=7680)
print('PASS AC-7.1: RESERVED -> ACCEPTED -> PAID; only bank evidence moves cash')

plan('EXECUTE_DROP_RESPONSE')
lost, body, headers = reserve('lost-response', 3000)
l = lost['payoutId']
unknown = detail(l, 'UNKNOWN', done=False)
check([h['toStatus'] for h in unknown['history']] == ['RESERVED', 'UNKNOWN'], 'Missing UNKNOWN transition')
balances('lost-response-reservation-retained', cash=7680, available=4680, payout_transit=3000)
recovered = detail(l, 'ACCEPTED')
check([h['toStatus'] for h in recovered['history']] == ['RESERVED', 'UNKNOWN', 'ACCEPTED'],
      'Recovery lost the UNKNOWN audit trail')
check(len(recovered['attempts']) == 2 and recovered['attempts'][0]['resultCode'] == 'NO_RESPONSE'
      and recovered['attempts'][1]['responseStatus'] == 200, 'Expected dropped response then successful query')
check(request(APP, '/api/payouts', 'POST', body, headers=headers, expected_status=201) == lost,
      'API replay must return the original RESERVED response')
external = [p for p in request(SIM, '/sim/bank/payments') if p['requestId'] == l]
check(len(external) == 1, 'Recovery made duplicate bank payments')
# Explicit same-request retry also returns the existing bank payment.
retry = request(SIM, '/sim/bank/payments', 'POST',
                {'requestId': l, 'amount': 3000, 'currency': 'CAD', 'destination': 'MERCHANT-001'})
check(retry['paymentId'] == external[0]['paymentId'], 'Bank retry changed payment identity')
advance_to('2026-10-22T12:00:00Z')
import_day('2026-10-22', 7680, 0, 3000, 4680)
paid(l, 3000)
balances('lost-response-paid-once', cash=4680, available=4680)
print('PASS AC-7.6/7.8: UNKNOWN retained funds; query/retry recovered one bank payment, one debit')

plan('REJECT')
rejected, _, _ = reserve('rejected', 1000)
f = rejected['payoutId']
failed = detail(f, 'FAILED')
check(failed['payout']['failureReason'] == 'ACCOUNT_CLOSED' and failed['bankLines'] == [], 'Wrong rejection')
entry = next(e for e in failed['entries'] if e['entryType'] == 'PAYOUT_FAILED')
check(entry['reversesEntryId'] is None and entry['correctionId'] is None, 'Failure must not be REVERSAL')
check(sorted((p['accountCode'], p['side'], p['amount']) for p in failed['postings'] if p['entryId'] == entry['id'])
      == [(2102, 'C', 1000), (2104, 'D', 1000)], 'Wrong release postings')
balances('rejected-released', cash=4680, available=4680)
print('PASS AC-7.22: REJECT -> FAILED, command DONE, reservation released without cash movement')

plan('RETURN_AFTER_DAYS', 1)
returned, _, _ = reserve('returned', 1000)
r = returned['payoutId']
detail(r, 'ACCEPTED')
balances('return-accepted', cash=4680, available=3680, payout_transit=1000)
advance_to('2026-10-23T12:00:00Z')
import_day('2026-10-23', 4680, 0, 1000, 3680)
paid(r, 1000)
balances('return-paid', cash=3680, available=3680)
advance_to('2026-10-26T12:00:00Z')  # One business day after Friday, across the weekend.
import_day('2026-10-26', 3680, 1000, 0, 4680)
paid(r, 1000, returned=True)
balances('returned', cash=4680, available=4680)
print('PASS AC-7.10/7.13: PAID -> RETURNED, cash restored; duplicate bank files have no effect')

external = save('final-bank-payments', request(SIM, '/sim/bank/payments'))
check(len(external) == 4 and {p['requestId']: p['status'] for p in external} ==
      {n: 'EXECUTED', l: 'EXECUTED', f: 'REJECTED', r: 'RETURNED'}, 'Unexpected bank payment set')
txns = save('final-bank-transactions', request(SIM, '/sim/bank/transactions'))
check(Counter(t['reference'] for t in txns) == Counter({batch: 1, n: 1, l: 1, r: 2}),
      'Unexpected bank debit/credit count; rejected payment must have no transaction')
check(sum(t['amount'] * (1 if t['direction'] == 'CREDIT' else -1) for t in txns) == 4680,
      'INV-11 bank cash differs from ledger')
processor = save('processor-final', request(APP, '/api/recon/processor-snapshots', 'POST',
                                          {'businessDate': '2026-10-19'}))
check(processor['status'] == 'ACCEPTED', 'Processor snapshot rejected')
before = balances('before-recon', cash=4680, available=4680)
as_of = request(APP, '/api/overview')['asOf']['at']
evidence = (instant(as_of).replace(microsecond=0) + timedelta(seconds=1)).isoformat()
print('Waiting for the unchanged 60s evidence grace...')
wait_for('evidence grace', lambda: request(APP, '/api/overview'),
         lambda o: instant(o['asOf']['at']) >= instant(evidence) + timedelta(seconds=60))
# Historical cutoff verifies cash by bank booking date. These new API reservations
# occur after cutoff, so exact payout/line matching is asserted above, not claimed
# as coverage of BankRule's reserved-before-cutoff branch (PayoutReconTest covers it).
report = save('reconciliation', request(APP, '/api/recon/runs', 'POST',
              {'kind': 'ALL', 'cutoffAt': cutoff, 'evidenceAsOf': evidence, 'dryRun': False}))
check(len(report['runIds']) == 3 and len(set(report['runIds'])) == 3, 'Expected three persisted runs')
check({run['kind'] for run in report['runs']} == {'PROCESSOR', 'BANK', 'AGING'}, 'Missing recon kind')
for run in report['runs']:
    check(run['status'] == 'FINISHED' and run['findings'] == [], f'Reconciliation failed: {run}')
    check(save('run-' + run['kind'].lower(), request(APP, '/api/recon/runs/' + run['id'])) == run,
          'Persisted report differs')
    inputs = {'PROCESSOR': [processor['fileId']], 'BANK': bank_files}.get(run['kind'], [])
    check(all(file_id in run['inputHashes'] for file_id in inputs), 'Reconciliation omitted evidence')
issues = save('issues', request(APP, '/api/recon/issues'))
check(issues['issues'] == [], f'Unexpected issues: {issues}')
check(balances('after-recon', cash=4680, available=4680) == before, 'Reconciliation changed business or ledger')
print('PASS ALL reconciliation: FINISHED, zero findings/issues; ledger unchanged; cash=available=4680')
print('PASS T11: four payout paths, one bank payment per request, balanced sealed ledger; evidence:', OUT)
PY
echo 'Smoke passed. Evidence: target/smoke-p5/ (services and volumes are removed on exit).'
