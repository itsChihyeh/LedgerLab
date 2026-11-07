"""AC-8.8 real payout batch, AC-8.6 refunds through real Stripe, AC-8.9 reconciliation on real data.

Exit codes: 0 pass, 1 fail, 75 still waiting (bank deposit or no settled payment yet).
"""
import json
import os
import re
import time
from datetime import date, datetime, timedelta, timezone
from urllib.parse import urlencode

from probe import VERSION
from runtime import (APP, SIM, OUT, ROOT, Failure, emit, get_json, instant, load_run, request, require,
                     safe, simulator_sql, utcnow, wait_until, write_json)

WAITING = 75
LINE_TYPES = {'charge': 'PAYMENT', 'payment': 'PAYMENT', 'refund': 'REFUND', 'payment_refund': 'REFUND'}
STRIPE_ID = re.compile(r'\b(?:po|txn|ch|re|evt)_[A-Za-z0-9]+')
REFUND_AMOUNT = 100


# ---------------------------------------------------------------- Stripe reads (through the outbound relay)

def stripe_get(path, params=()):
    key = os.environ.get('STRIPE_API_KEY', '')
    require(re.fullmatch(r'(rk|sk)_test_[A-Za-z0-9_]+', key), 'INVALID_TEST_KEY')
    query = '?' + urlencode(list(params)) if params else ''
    status, body = request(SIM, '/relay/stripe-api' + path + query,
                           headers={'Authorization': 'Bearer ' + key, 'Stripe-Version': VERSION}, timeout=70)
    require(status == 200, 'STRIPE_READ_HTTP_FAILED')
    value = json.loads(body)
    require(isinstance(value, dict) and value.get('livemode', False) is False, 'STRIPE_READ_INVALID')
    return value


def collect_pages(fetch, prefix):
    """Every page or nothing: malformed pages and cursor loops fail closed."""
    items, cursors, cursor = [], set(), None
    while True:
        page = fetch(cursor)
        require(isinstance(page, dict) and isinstance(page.get('data'), list)
                and type(page.get('has_more')) is bool, 'INVALID_STRIPE_PAGE')
        for item in page['data']:
            require(isinstance(item, dict) and re.fullmatch(prefix + r'_[A-Za-z0-9]+', str(item.get('id', ''))),
                    'INVALID_STRIPE_PAGE_ITEM')
            items.append(item)
        if not page['has_more']:
            return items
        require(bool(page['data']), 'EMPTY_NONFINAL_PAGE')
        cursor = page['data'][-1]['id']
        require(cursor not in cursors, 'STRIPE_CURSOR_LOOP')
        cursors.add(cursor)


def list_all(path, prefix, params=()):
    def fetch(cursor):
        query = [*params, ('limit', 100)] + ([('starting_after', cursor)] if cursor else [])
        return stripe_get(path, query)
    return collect_pages(fetch, prefix)


# ---------------------------------------------------------------- AC-8.8 pure checks

def stripe_lines(rows):
    """Balance transactions of one payout, minus the payout row itself (not a batch line by design)."""
    lines = {}
    for row in rows:
        if row.get('type') == 'payout':
            continue
        require(all(type(row.get(k)) is int for k in ('amount', 'fee', 'net')) and row['id'] not in lines,
                'INVALID_BALANCE_TRANSACTION')
        lines[row['id']] = (LINE_TYPES.get(row.get('type'), 'UNMAPPED:' + str(row.get('type'))),
                            row['amount'], row['fee'], row['net'])
    return lines


def local_lines(lines):
    result = {}
    for line in lines:
        require(line['balanceTxnId'] not in result, 'DUPLICATE_LOCAL_LINE')
        result[line['balanceTxnId']] = (line['lineType'], line['gross'], line['fee'], line['net'])
    return result


def line_differences(stripe, local):
    problems = []
    for txn in sorted(set(stripe) | set(local)):
        if txn not in local:
            problems.append({'txn': txn, 'problem': 'MISSING_LOCALLY', 'stripe': stripe[txn]})
        elif txn not in stripe:
            problems.append({'txn': txn, 'problem': 'NOT_IN_STRIPE', 'local': local[txn]})
        elif stripe[txn] != local[txn]:
            problems.append({'txn': txn, 'problem': 'DIFFERENT', 'stripe': stripe[txn], 'local': local[txn]})
    return problems


def judge_batch(detail, payout):
    """Returns (verdict, facts). Verdicts: FAIL, WAITING_BANK, PASS. HELD is always FAIL and never repaired."""
    batch = detail['batch']
    facts = {'batch': batch['batchId'], 'status': batch['status'], 'payout_amount': payout['amount']}
    if batch['status'] == 'HELD':
        reasons = [{'hold_reason': h.get('holdReason'), 'evidence': h.get('evidenceId')}
                   for h in detail.get('history', []) if h.get('holdReason')]
        facts.update(hold_reason=batch.get('holdReason'), reasons=reasons,
                     lines_with_unmapped_or_missing_ref=[l['balanceTxnId'] for l in detail['lines']
                                                         if not l.get('processorRef')])
        return 'FAIL', dict(facts, reason='BATCH_HELD')
    settled = [e for e in detail.get('entries', []) if e['entryType'] == 'BATCH_SETTLED']
    facts['settled_entries'] = len(settled)
    if len(settled) != 1:
        return 'FAIL', dict(facts, reason='BATCH_SETTLED_ENTRY_COUNT')
    if not (batch['payoutAmount'] == payout['amount'] == batch['net']):
        return 'FAIL', dict(facts, reason='PAYOUT_AMOUNT_NET_MISMATCH', batch_net=batch['net'],
                            batch_payout_amount=batch['payoutAmount'])
    if batch['status'] == 'BANKED':
        return 'PASS', facts
    if batch['status'] == 'CONFIRMED':
        return 'WAITING_BANK', facts
    return 'FAIL', dict(facts, reason='UNEXPECTED_BATCH_STATUS')


# ---------------------------------------------------------------- shared run scaffolding

def finish(report, started_path_prefix):
    report['finished_at'] = instant()
    path = OUT / (started_path_prefix + utcnow().strftime('%Y%m%dT%H%M%S%fZ') + '.json')
    write_json(path, json.loads(safe(json.dumps(report, ensure_ascii=False))))
    summary = {key: report.get(key) for key in ('ac', 'result', 'reason')}
    summary['report'] = path.relative_to(ROOT).as_posix()
    emit(summary)


def guarded(report, body):
    code = 1
    try:
        code = body()
    except Failure as error:
        report['reason'], code = str(error), error.exit_code
        report['result'] = 'WAITING' if code == WAITING else 'FAIL'
    except (OSError, ValueError, KeyError, TypeError):
        report['reason'] = 'INVALID_RESPONSE_OR_LOCAL_IO'
    return code


def audit_clean():
    value = get_json(APP, '/api/audit/invariants')
    require(isinstance(value.get('violations'), list) and value.get('checkedAt'), 'INVALID_AUDIT_RESULT')
    require(not value['violations'], 'INVARIANT_VIOLATIONS')
    return {'checkedAt': value['checkedAt'], 'violations': value['violations']}


def settled_payments(limit=60):
    """Settled, not yet refunded charges taken from the local batches, in a stable order."""
    batches = get_json(APP, '/api/batches')['batches']
    refs = []
    for batch in sorted(batches, key=lambda b: b['batchId']):
        if batch['status'] not in ('CONFIRMED', 'BANKED'):
            continue
        for line in get_json(APP, '/api/batches/' + batch['batchId'])['lines']:
            if line['lineType'] == 'PAYMENT' and line['processorRef'].startswith('ch_'):
                refs.append(line['processorRef'])
    return sorted(set(refs))[:limit]


# ---------------------------------------------------------------- AC-8.8

def accept_payout(sync_bank=None, payouts=None):
    from check_payout import check_payout
    from probe import Stripe
    report = {'ac': 'AC-8.8', 'started_at': instant(), 'result': 'FAIL', 'batches': []}

    def body():
        run = load_run()
        report['run_id'] = run['run_id']
        code, ready = (payouts or (lambda: check_payout(Stripe(os.environ.get('STRIPE_API_KEY', '')),
                                                          run['window_start'])))()
        if code != 0:
            raise Failure('WAITING_FOR_PAYOUT', WAITING)
        if sync_bank is None:
            from catchup import sync_bank as default_sync
            default_sync(run, report)
        else:
            sync_bank(run, report)
        verdicts = []
        for po in ready['payouts']:
            payout = stripe_get('/v1/payouts/' + po)
            rows = list_all('/v1/balance_transactions', 'txn', [('payout', po)])
            detail = get_json(APP, '/api/batches/' + po, missing_ok=True)
            if detail is None:
                verdicts.append('FAIL')
                report['batches'].append({'batch': po, 'verdict': 'FAIL', 'reason': 'BATCH_NOT_IN_LEDGER'})
                continue
            verdict, facts = judge_batch(detail, payout)
            differences = line_differences(stripe_lines(rows), local_lines(detail['lines']))
            if differences and verdict != 'FAIL':
                verdict, facts = 'FAIL', dict(facts, reason='LINES_DIFFER_FROM_STRIPE')
            facts.update(verdict=verdict, stripe_lines=len(stripe_lines(rows)), local_lines=len(detail['lines']),
                         differences=differences, stripe_payout_status=payout.get('status'),
                         stripe_payout_created=payout.get('created'), arrival_date=payout.get('arrival_date'),
                         events=[{'type': d['eventType'], 'received_at': d['receivedAt']}
                                 for d in detail.get('deliveries', [])])
            report['batches'].append(facts)
            verdicts.append(verdict)
            emit({'payout': po, 'verdict': verdict, 'status': facts['status'],
                  'differences': len(differences), 'reason': facts.get('reason'),
                  'hold_reason': facts.get('hold_reason'), 'reasons': facts.get('reasons')})
        report['invariants'] = audit_clean()
        if 'FAIL' in verdicts:
            raise Failure('BATCH_CHECK_FAILED')
        if 'WAITING_BANK' in verdicts:
            report['result'] = 'WAITING'
            report['reason'] = '等待银行入账'
            emit({'result': '等待银行入账'})
            return WAITING
        report['result'] = 'PASS'
        return 0

    return guarded(report, body), report, 'accept-ac88-'


# ---------------------------------------------------------------- AC-8.6

def own_refund_refs():
    return {'a': 't07-ac86-a', 'b': 't07-ac86-b'}


def pick_payments(candidates, refunded):
    """Two distinct payments; ones already refunded by this script are reused so reruns stay idempotent."""
    mine = [c for c in candidates if refunded.get(c) in own_refund_refs().values()]
    fresh = [c for c in candidates if c not in refunded]
    by_ref = {refunded[c]: c for c in mine}
    chosen = {}
    for label, ref in own_refund_refs().items():
        chosen[label] = by_ref.get(ref) or next((c for c in fresh if c not in chosen.values()), None)
    require(all(chosen.values()), 'NEED_TWO_SETTLED_PAYMENTS')
    return chosen


def refund_state(detail):
    return {'status': detail['refund']['status'], 'command': (detail['commands'] or [{}])[-1].get('status'),
            'attempts': [(a['attempt_no'], a['response_status'], a['result_code']) for a in detail['attempts']]}


def stripe_refunds(charge):
    return list_all('/v1/refunds', 're', [('charge', charge)])


def post_refund(charge, label):
    ref = own_refund_refs()[label]
    status, raw = request(APP, '/api/refunds', 'POST',
                          {'payment_ref': charge, 'merchant_refund_ref': ref, 'amount': REFUND_AMOUNT},
                          {'Idempotency-Key': ref})
    require(status in (200, 201), 'REFUND_NOT_ACCEPTED_HTTP_' + str(status))
    return json.loads(raw)['refund_id']


def watch_refund(refund_id, until, seconds=240):
    """Poll quickly so a short-lived UNKNOWN command is seen when it happens."""
    seen = []

    def read():
        detail = get_json(APP, '/api/refunds/' + refund_id)
        state = refund_state(detail)
        if not seen or seen[-1] != (state['status'], state['command']):
            seen.append((state['status'], state['command']))
        return detail
    detail = wait_until(read, until, seconds, interval=0.25)
    return detail, seen


def assert_one_refund(charge, refund_id):
    refunds = stripe_refunds(charge)
    require(len(refunds) == 1, 'STRIPE_REFUND_COUNT_' + str(len(refunds)))
    require((refunds[0].get('metadata') or {}).get('ledgerlab_refund_id') == refund_id, 'STRIPE_METADATA_MISMATCH')
    require(refunds[0].get('amount') == REFUND_AMOUNT and refunds[0].get('charge') == charge, 'STRIPE_REFUND_FIELDS')
    return refunds[0]


def accept_refunds():
    report = {'ac': 'AC-8.6', 'started_at': instant(), 'result': 'FAIL'}
    plan_owned = []

    def body():
        run = load_run()
        report['run_id'] = run['run_id']
        candidates = settled_payments()
        if len(candidates) < 2:
            raise Failure('WAITING_FOR_SETTLED_PAYMENT', WAITING)
        refunded = {}
        for charge in candidates:
            for r in get_json(APP, '/api/payments/' + charge).get('refunds', []):
                refunded[charge] = r.get('merchant_refund_ref')
        chosen = pick_payments(candidates, refunded)
        report['payments'] = chosen
        require(get_json(SIM, '/sim/relay/plan')['rules'] == [], 'RELAY_PLAN_NOT_EMPTY')
        # a: plain refund
        a_id = post_refund(chosen['a'], 'a')
        detail, seen = watch_refund(a_id, lambda d: d['refund']['status'] == 'SUCCEEDED')
        stripe_a = assert_one_refund(chosen['a'], a_id)
        report['a'] = {'refund_id': a_id, 'charge': chosen['a'], 'local_status': detail['refund']['status'],
                       'stripe_refund': stripe_a['id'], 'stripe_status': stripe_a.get('status'),
                       'local_processor_ref': detail['refund']['processor_refund_ref'], 'seen': seen}
        require(detail['refund']['processor_refund_ref'] == stripe_a['id'], 'LOCAL_STRIPE_REFUND_REF_MISMATCH')
        # b: lose the response
        since = get_json(SIM, '/sim/clock')['now']
        stats_path = '/sim/relay/stats?' + urlencode({'since': since})
        before = get_json(SIM, stats_path)['outbound']
        already = get_json(APP, '/api/payments/' + chosen['b']).get('refunds', [])
        if not already:
            plan_owned.append(True)
            get_json(SIM, '/sim/relay/plan', 'PUT', {'rules': [{'direction': 'OUTBOUND', 'method': 'POST',
                                                                'path_prefix': '/v1/refunds',
                                                                'action': 'DROP_RESPONSE', 'times': 1}]})
        b_id = post_refund(chosen['b'], 'b')
        detail, seen = watch_refund(b_id, lambda d: d['refund']['status'] in ('SUBMITTED', 'SUCCEEDED')
                                    and d['commands'] and d['commands'][-1]['status'] == 'DONE')
        after = get_json(SIM, stats_path)['outbound']
        delta = {key: after[key] - before[key] for key in after}
        lost = [a for a in detail['attempts'] if a['response_status'] is None or a['response_status'] >= 500]
        unknown_history = [h for h in detail['history'] if h['to_status'] == 'UNKNOWN']
        stripe_b = assert_one_refund(chosen['b'], b_id)
        report['b'] = {'refund_id': b_id, 'charge': chosen['b'], 'local_status': detail['refund']['status'],
                       'stripe_refund': stripe_b['id'], 'stripe_status': stripe_b.get('status'),
                       'seen': seen, 'attempts': refund_state(detail)['attempts'],
                       'unknown_history_rows': len(unknown_history), 'relay_delta': delta}
        if not already:
            require(delta['DROP_RESPONSE'] == 1, 'DROP_RESPONSE_COUNT_MISMATCH')
            require(lost and (unknown_history or any(s[1] == 'UNKNOWN' for s in seen)), 'NO_EVIDENCE_OF_UNKNOWN')
            require(len(detail['attempts']) >= 2, 'NO_QUERY_BEFORE_RESEND')
        require(detail['refund']['processor_refund_ref'] == stripe_b['id'], 'LOCAL_STRIPE_REFUND_REF_MISMATCH')
        report['stripe_refund_statuses'] = sorted({stripe_a.get('status'), stripe_b.get('status')})
        report['pending_observed'] = 'pending' in report['stripe_refund_statuses']
        report['invariants'] = audit_clean()
        report['result'] = 'PASS'
        return 0

    code = guarded(report, body)
    if plan_owned:
        try:
            get_json(SIM, '/sim/relay/plan', 'PUT', {'rules': []})
            report['plan_restored'] = True
        except (Failure, OSError, ValueError, KeyError, TypeError):
            report.update(result='FAIL', plan_restored=False, reason='RELAY_PLAN_RESTORE_FAILED')
            code = 1
    return code, report, 'accept-ac86-'


# ---------------------------------------------------------------- AC-8.9

def toronto_day(run, moment):
    return date.fromisoformat(simulator_sql(run, "SELECT to_json(('" + instant(moment)
                                            + "'::timestamptz AT TIME ZONE 'America/Toronto')::date);"))


def business_dates(first, last):
    return [(first + timedelta(days=i)).isoformat() for i in range((last - first).days + 1)]


def toronto_midnight(run, day):
    # The reconciliation API only accepts a Toronto-midnight cutoff (not the task text's "current hour").
    epoch = simulator_sql(run, "SELECT to_json(extract(epoch FROM ('" + day.isoformat()
                          + "'::timestamp AT TIME ZONE 'America/Toronto'))::bigint);")
    return datetime.fromtimestamp(epoch, timezone.utc)


def finding_ids(finding):
    texts = [finding.get('subjectId', ''), finding.get('evidenceRefs', ''), finding.get('actual', ''),
             finding.get('expected', '')]
    return sorted(set(STRIPE_ID.findall(' '.join(str(t) for t in texts))))


def classify_findings(runs):
    rows = []
    for run in runs:
        for observed in run['findings']:
            f = observed['finding']
            subject = f['subjectId']
            rows.append({'run_kind': run['kind'], 'category': f['category'], 'subject_type': f['subjectType'],
                         'subject': subject, 'stripe_subject': bool(re.fullmatch(r'(po|txn|ch|re|evt)_[A-Za-z0-9]+', subject)),
                         'traced_ids': finding_ids(f), 'fingerprint': observed['fingerprint']})
    return rows


def comparable(report):
    return {'hashes': sorted(report['inputHashes'].values()) if 'inputHashes' in report else None,
            'findings': sorted(o['fingerprint'] for o in report['findings']),
            'cutoffAt': report['cutoffAt'], 'evidenceAsOf': report['evidenceAsOf']}


def accept_recon():
    report = {'ac': 'AC-8.9', 'started_at': instant(), 'result': 'FAIL'}

    def body():
        run = load_run()
        report['run_id'] = run['run_id']
        now = utcnow()
        window = datetime.fromisoformat(run['window_start'].replace('Z', '+00:00'))
        today = toronto_day(run, now)
        dates = business_dates(toronto_day(run, window), today)
        snapshots = report['snapshots'] = []
        for day in dates:
            status, raw = request(APP, '/api/recon/processor-snapshots', 'POST', {'businessDate': day}, timeout=120)
            require(status == 200, 'SNAPSHOT_HTTP_' + str(status))
            snap = json.loads(raw)
            snapshots.append({'date': day, 'file_id': snap['fileId'], 'status': snap['status'],
                              'reject_reason': snap.get('rejectReason')})
            require(snap['status'] == 'ACCEPTED', 'SNAPSHOT_REJECTED_' + day)
        cutoff = toronto_midnight(run, today)
        evidence = (utcnow().replace(microsecond=0) + timedelta(seconds=1))
        report.update(cutoff_at=instant(cutoff), evidence_as_of=instant(evidence))
        wait_until(lambda: datetime.fromisoformat(get_json(APP, '/api/overview')['asOf']['at'].replace('Z', '+00:00')),
                   lambda at: at >= evidence + timedelta(seconds=60), 150, interval=2)
        body_in = {'kind': 'ALL', 'cutoffAt': instant(cutoff), 'evidenceAsOf': instant(evidence)}
        first = get_json(APP, '/api/recon/runs', 'POST', dict(body_in, dryRun=False), timeout=300)
        runs = first['runs']
        processor = next(r for r in runs if r['kind'] == 'PROCESSOR')
        require(processor['status'] == 'FINISHED', 'PROCESSOR_RUN_NOT_FINISHED')
        require(processor['cutoffAt'] and processor['inputHashes']
                and all(re.fullmatch(r'[0-9a-f]{64}', h) for h in processor['inputHashes'].values())
                and {s['file_id'] for s in snapshots} & set(processor['inputHashes']), 'SNAPSHOT_ARCHIVE_NOT_HASHED')
        report['run_ids'] = first['runIds']
        rows = report['findings'] = classify_findings(runs)
        for row in rows:
            emit({'finding': row['category'], 'subject': row['subject'], 'traced': row['traced_ids']})
        report['finding_count'] = len(rows)
        report['untraceable'] = [r for r in rows if not r['stripe_subject'] and not r['traced_ids']]
        require(not report['untraceable'], 'FINDING_NOT_TRACEABLE_TO_STRIPE_ID')
        replay = get_json(APP, '/api/recon/runs', 'POST', dict(body_in, dryRun=True), timeout=300)
        mine = {r['kind']: comparable(r) for r in runs}
        again = {r['kind']: comparable(r) for r in replay['runs']}
        report['replay_identical'] = mine == again
        require(mine == again, 'REPLAY_DIFFERS')
        report['invariants'] = audit_clean()
        report['result'] = 'PASS'
        return 0

    return guarded(report, body), report, 'accept-ac89-'


def run_ac(which):
    code, report, prefix = {'ac-8.8': accept_payout, 'ac-8.6': accept_refunds, 'ac-8.9': accept_recon}[which]()
    finish(report, prefix)
    return code
