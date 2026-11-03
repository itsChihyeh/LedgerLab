"""Controlled real duplicate/reorder acceptance, reusing six seed-2 identities."""
import json
from types import SimpleNamespace
from urllib.parse import urlencode

from accept import current_payments, traffic, wait_for_captured
from catchup import resend
from runtime import (APP, SIM, OUT, ROOT, Failure, decode_recording, emit, get_json, instant,
                     load_run, recordings, require, safe, simulator_sql, utcnow, wait_until, write_json)

CARD = 'pm_card_bypassPending'


def batch(rows):
    return sorted([row for row in rows if row['card'] == CARD and row['seed'] == 2
                   and 1 <= row['ordinal'] <= 6], key=lambda row: row['ordinal'])


def audit():
    value = get_json(APP, '/api/audit/invariants')
    require(isinstance(value.get('violations'), list) and value.get('checkedAt'), 'INVALID_AUDIT_RESULT')
    require(not value['violations'], 'INVARIANT_VIOLATIONS')
    return {'checkedAt': value['checkedAt'], 'violations': value['violations']}


def payment_evidence(charge):
    payment = get_json(APP, '/api/payments/' + charge)
    require(payment['processorPaymentRef'] == charge and payment['status'] == 'CAPTURED', 'PAYMENT_NOT_CAPTURED')
    capture = [p for p in payment['postings'] if p['entryType'] == 'PAYMENT_CAPTURED']
    entries = {p['entryId'] for p in capture}
    require(len(entries) == 1 and len(capture) == 2 and {p['side'] for p in capture} == {'D', 'C'}
            and all(p['amount'] == payment['amount'] for p in capture), 'CAPTURE_NOT_EXACTLY_ONCE')
    return {'charge': charge, 'payment_id': payment['id'], 'status': payment['status'],
            'capture_entry_id': next(iter(entries)), 'amount': payment['amount']}


def inbox_evidence(event_id, minimum, before=None):
    inbox = get_json(APP, '/api/events/' + event_id)
    require(inbox['eventId'] == event_id and inbox['status'] == 'PROCESSED' and inbox['result'] == 'APPLIED'
            and inbox['livemode'] is False, 'INBOX_NOT_PROCESSED_ONCE')
    deliveries = inbox['deliveries']
    require(sum(d['outcome'] == 'NEW' for d in deliveries) == 1
            and all(d['outcome'] in ('NEW', 'DUPLICATE') for d in deliveries), 'INBOX_MULTIPLE_BUSINESS_RESULTS')
    require(len(deliveries) >= minimum, 'MISSING_DUPLICATE_DELIVERIES')
    result = {'event_id': event_id, 'status': inbox['status'], 'result': inbox['result'],
              'subject_id': inbox['subjectId'], 'deliveries': len(deliveries),
              'delivery_ids': [d['id'] for d in deliveries]}
    if before:
        require(result['subject_id'] == before['subject_id'] and result['result'] == before['result']
                and set(before['delivery_ids']).issubset(result['delivery_ids']), 'INBOX_BUSINESS_RESULT_CHANGED')
    return result


def find_events(run, charges):
    def observed():
        found = {}
        for row in recordings(run, charges):
            _, event = decode_recording(row)
            charge = event['data']['object']['id']
            require(charge not in found or found[charge] == event['id'], 'MULTIPLE_CHARGE_SUCCESS_EVENTS')
            found[charge] = event['id']
        return found
    return wait_until(observed, lambda found: set(found) == set(charges), 60)


def relay_rows(run, after):
    require(type(after) is int and after >= 0, 'INVALID_RELAY_CURSOR')
    return simulator_sql(run, "SELECT coalesce(json_agg(json_build_object('id',id,'event_id',event_id,"
                         "'receipt_id',receipt_id,'action',action,'attempt_no',attempt_no,"
                         "'http_status',http_status) ORDER BY id),'[]') FROM sim_chaos.relay_log WHERE id > "
                         + str(after) + ';')


def assert_reorder(rows, first, others):
    held = [row for row in rows if row['event_id'] == first and row['action'] == 'HOLD_AND_REORDER']
    arrivals = [row for row in held if row['attempt_no'] == 0]
    releases = [row for row in held if row['attempt_no'] > 0]
    require(len(arrivals) == 1 and len(releases) == 1, 'HOLD_NOT_RECEIVED_AND_RELEASED_ONCE')
    arrival, release = arrivals[0], releases[0]
    require(arrival['receipt_id'] == release['receipt_id'] and release['http_status'] == 200,
            'HOLD_RELEASE_FAILED')
    overtakers = {row['receipt_id'] for row in rows if row['event_id'] in others
                 and row['action'] == 'DUPLICATE' and row['attempt_no'] == 3 and row['http_status'] == 200
                 and arrival['id'] < row['id'] < release['id']}
    require(len(overtakers) == 2, 'HOLD_NOT_RELEASED_AFTER_TWO_COMPLETE_EVENTS')
    return {'held_event': first, 'overtaking_events': 2, 'release_http_status': 200}


def assert_copies(rows, events):
    for event_id in events:
        received = [r for r in rows if r['event_id'] == event_id and r['action'] == 'DUPLICATE'
                    and r['attempt_no'] == 0]
        require(len(received) == 1, 'DUPLICATE_RECEIPT_COUNT_MISMATCH')
        forwarded = [r for r in rows if r['receipt_id'] == received[0]['receipt_id'] and r['attempt_no'] > 0]
        require(sorted(r['attempt_no'] for r in forwarded) == [1, 2, 3]
                and all(r['http_status'] == 200 for r in forwarded), 'DUPLICATE_COPY_NOT_ACCEPTED')


def set_plan(rules):
    get_json(SIM, '/sim/relay/plan', 'PUT', {'rules': rules})


def assert_payment_link(inbox, payment):
    require(inbox['subject_id'] == payment['charge'], 'EVENT_PAYMENT_ID_MISMATCH')


def accept_duplicates():
    report = {'ac': 'AC-8.5', 'started_at': instant(), 'result': 'FAIL', 'resend_count': 0}
    plan_owned = False
    try:
        run = load_run()
        report['run_id'] = run['run_id']
        require(get_json(SIM, '/sim/relay/plan')['rules'] == [], 'RELAY_PLAN_NOT_EMPTY')
        rows = current_payments(run)
        # Capture each existing run payment's state, including non-CAPTURED states.
        baseline = []
        for row in rows:
            if row['charge']:
                value = get_json(APP, '/api/payments/' + row['charge'], missing_ok=True)
                baseline.append({'charge': row['charge'], 'status': value['status'] if value else 'MISSING'})
        report['baseline'] = {'payments': baseline, 'invariants': audit()}
        old = [p['charge'] for p in baseline if p['status'] == 'CAPTURED'
               and p['charge'] not in {r['charge'] for r in batch(rows)}][:3]
        require(len(old) == 3, 'REQUIRES_THREE_CAPTURED_PAYMENTS_OUTSIDE_SEED_2')
        events = find_events(run, old)
        before_payments = {ch: payment_evidence(ch) for ch in old}
        before_inbox = {events[ch]: inbox_evidence(events[ch], 1) for ch in old}
        report['baseline']['replayed_payments'] = list(before_payments.values())
        report['baseline']['replayed_inbox'] = list(before_inbox.values())

        def replay(event_id):
            report['resend_count'] += 1
            resend(event_id)

        def wait_deliveries(event_id, minimum):
            wait_until(lambda: get_json(APP, '/api/events/' + event_id, missing_ok=True),
                       lambda value: value is not None and value['status'] == 'PROCESSED'
                       and len(value['deliveries']) >= minimum, 60)

        for event_id, before in before_inbox.items():
            replay(event_id)
            replay(event_id)
            wait_deliveries(event_id, before['deliveries'] + 2)

        existing = {r['ordinal']: r['charge'] for r in batch(rows) if r['status'] == 'succeeded'}
        report['new_payment_count'] = 6 - len(existing)
        since = get_json(SIM, '/sim/clock')['now']
        stats_path = '/sim/relay/stats?' + urlencode({'since': since})
        stats_before = get_json(SIM, stats_path)['inbound']
        cursor = simulator_sql(run, 'SELECT coalesce(max(id),0) FROM sim_chaos.relay_log;')
        duplicate = {'event_type': 'charge.succeeded', 'action': 'DUPLICATE', 'copies': 3}
        hold = {'event_type': 'charge.succeeded', 'action': 'HOLD_AND_REORDER', 'after_events': 2}
        plan_owned = True
        set_plan([hold, duplicate])
        # count=1 and count=6 share identities. A retry replays saved events, never changes the seed.
        traffic(SimpleNamespace(count=1, card=CARD, seed=2))
        first_row = batch(current_payments(run))[0]
        first = find_events(run, [first_row['charge']])[first_row['charge']]
        if 1 in existing:
            replay(first)
        wait_until(lambda: relay_rows(run, cursor),
                   lambda logs: any(r['event_id'] == first and r['action'] == 'HOLD_AND_REORDER'
                                    and r['attempt_no'] == 0 for r in logs), 60)
        set_plan([dict(hold, event_id=first), duplicate])
        traffic(SimpleNamespace(count=6, card=CARD, seed=2))
        generated = batch(current_payments(run))
        require(len(generated) == 6 and all(r['status'] == 'succeeded' for r in generated), 'TRAFFIC_NOT_SIX_SUCCESSES')
        charges = [r['charge'] for r in generated]
        events.update(find_events(run, charges))
        for row in generated[1:]:
            if row['ordinal'] in existing:
                replay(events[row['charge']])
        wait_for_captured(run, old + charges, report, seconds=120)
        wait_until(lambda: relay_rows(run, cursor),
                   lambda logs: sum(r['action'] == 'DUPLICATE' and r['attempt_no'] == 3 for r in logs) >= 5, 60)
        logs = relay_rows(run, cursor)
        assert_copies(logs, {events[ch] for ch in charges[1:]})
        report['reorder'] = assert_reorder(logs, first, {events[ch] for ch in charges[1:]})
        stats_after = get_json(SIM, stats_path)['inbound']
        delta = {key: stats_after[key] - stats_before[key] for key in stats_after}
        report['relay_counts'] = delta
        require(delta['DUPLICATE'] == 5 and delta['HOLD_AND_REORDER'] == 1
                and delta['HOLD_TIMEOUT_RELEASE'] == 0, 'RELAY_PLAN_COUNTS_MISMATCH')
        evidence = report['evidence'] = []
        for charge in old + charges:
            event_id = events[charge]
            before = before_inbox.get(event_id)
            minimum = before['deliveries'] + 2 if before else (1 if event_id == first else 3)
            inbox = inbox_evidence(event_id, minimum, before)
            payment = payment_evidence(charge)
            assert_payment_link(inbox, payment)
            if charge in before_payments:
                require(payment == before_payments[charge], 'REPLAY_CHANGED_PAYMENT_OR_CAPTURE')
            evidence.append(dict(payment, inbox=inbox))
        require(len({p['charge'] for p in evidence}) == 9 and len({p['payment_id'] for p in evidence}) == 9,
                'PAYMENTS_NOT_NINE_UNIQUE_FACTS')
        report['evidence'] = evidence
        report['invariants'] = audit()
        report['result'] = 'PASS'
    except Failure as error:
        report['reason'] = str(error)
    except (OSError, ValueError, KeyError, TypeError):
        report['reason'] = 'INVALID_RESPONSE_OR_LOCAL_IO'
    finally:
        if plan_owned:
            try:
                set_plan([])
                report['plan_restored'] = True
            except (Failure, OSError, ValueError, KeyError, TypeError):
                report.update(result='FAIL', plan_restored=False, reason='RELAY_PLAN_RESTORE_FAILED')
        report['finished_at'] = instant()
        path = OUT / ('accept-' + utcnow().strftime('%Y%m%dT%H%M%S%fZ') + '.json')
        write_json(path, json.loads(safe(json.dumps(report, ensure_ascii=False))))
        emit({'ac': report['ac'], 'result': report['result'], 'reason': report.get('reason'),
              'report': path.relative_to(ROOT).as_posix(), 'resend_count': report['resend_count']})
    return 0 if report['result'] == 'PASS' else 1
