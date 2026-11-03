"""AC-8.4: persistently discover real payments, then check signed intake and audit."""
import argparse
import hashlib
import hmac
import json
import re
import sys
import time

from runtime import (APP, SIM, ROOT, OUT, Failure, decode_recording, emit, get_json, instant,
                     load_run, load_secret, recordings, request, require, safe, utcnow, write_json)
from probe import VERSION


def latest_payments(rows, run_id):
    latest = {}
    for row in rows:
        require(row['run_id'] == run_id, 'SIMULATOR_RUN_MISMATCH_RUN_UP_FIRST')
        identity = row['card'], row['seed'], row['ordinal']
        if identity not in latest or row['id'] > latest[identity]['id']:
            latest[identity] = row
    return [latest[key] for key in sorted(latest)]


def current_payments(run):
    summary = get_json(SIM, '/sim/sandbox/summary')
    require(summary['run_id'] == run['run_id'], 'SIMULATOR_RUN_MISMATCH_RUN_UP_FIRST')
    return latest_payments(get_json(SIM, '/sim/sandbox/payments'), run['run_id'])


def successful_charges(rows):
    require(len(rows) >= 20, 'AC_8_4_REQUIRES_AT_LEAST_20_PAYMENTS')
    require(all(row['status'] == 'succeeded' and not row['error_code'] for row in rows),
            'GENERATED_PAYMENT_NOT_SUCCEEDED')
    charges = [row['charge'] for row in rows]
    require(len(set(charges)) == len(rows) and all(re.fullmatch(r'ch_[A-Za-z0-9]+', ch) for ch in charges),
            'INVALID_OR_DUPLICATE_CHARGE')
    return charges


def signature(secret, body, timestamp):
    signed = str(timestamp).encode('ascii') + b'.' + body
    return 't=' + str(timestamp) + ',v1=' + hmac.new(secret.encode('ascii'), signed, hashlib.sha256).hexdigest()


def negative_cases(body, secret, timestamp):
    require(body.startswith(b'{'), 'RECORDED_BODY_NOT_JSON_OBJECT')
    # Change exactly one byte; the unchanged signature is over the original bytes.
    return [('tampered_body', b'[' + body[1:], signature(secret, body, timestamp)),
            ('missing_signature', body, None),
            ('expired_400_seconds', body, signature(secret, body, timestamp - 400))]


def traffic(args):
    require(1 <= args.count <= 50, 'COUNT_MUST_BE_1_TO_50')
    require(-(2**63) <= args.seed < 2**63, 'SEED_OUT_OF_LONG_RANGE')
    run = load_run()
    current = current_payments(run)
    identities = {(row['card'], row['seed'], row['ordinal']) for row in current}
    identities.update((args.card, args.seed, ordinal) for ordinal in range(1, args.count + 1))
    require(len(identities) <= 50, 'RUN_PAYMENT_LIMIT_50')
    response = get_json(SIM, '/sim/sandbox/payments', 'POST',
                        {'count': args.count, 'card': args.card, 'seed': args.seed}, timeout=1800)
    evidence = {'run_id': run['run_id'], 'requested': args.count, 'card': args.card, 'seed': args.seed,
                'succeeded': response['succeeded'], 'failed': response['failed'], 'at': instant()}
    write_json(OUT / ('traffic-' + utcnow().strftime('%Y%m%dT%H%M%S%fZ') + '.json'), evidence)
    emit(evidence)
    require(response['count'] == args.count and response['succeeded'] == args.count and response['failed'] == 0,
            'TRAFFIC_PARTIALLY_FAILED_REVIEW_PERSISTED_ATTEMPTS')


def wait_for_captured(run, charges, report, seconds=300, monotonic=time.monotonic, sleep=time.sleep):
    deadline = monotonic() + seconds
    pending = set(charges)
    evidence = []
    report['payments'] = evidence
    first_body = None

    def remaining():
        duration = deadline - monotonic()
        require(duration > 0, 'CAPTURE_WAIT_TIMEOUT')
        return duration

    while pending:
        by_charge = {}
        for row in recordings(run, sorted(pending), timeout=min(20, remaining())):
            body, event = decode_recording(row)
            by_charge.setdefault(event['data']['object']['id'], (row, body, event))
        for charge in sorted(pending):
            payment = get_json(APP, '/api/payments/' + charge, missing_ok=True, timeout=min(5, remaining()))
            if payment is None or payment.get('status') != 'CAPTURED' or charge not in by_charge:
                continue
            require(payment.get('processorPaymentRef') == charge, 'PAYMENT_REFERENCE_MISMATCH')
            row, body, event = by_charge[charge]
            require(re.fullmatch(r'evt_[A-Za-z0-9]+', event['id']), 'INVALID_EVENT_ID')
            inbox = get_json(APP, '/api/events/' + event['id'], missing_ok=True, timeout=min(5, remaining()))
            if inbox is None:
                continue
            require(inbox.get('eventId') == event['id'] and inbox.get('eventType') == 'charge.succeeded'
                    and inbox.get('livemode') is False, 'INBOX_EVENT_MISMATCH')
            require(event.get('api_version') == VERSION and inbox.get('apiVersion') == VERSION,
                    'API_VERSION_MISMATCH_REQUIRES_SEPARATE_CHANGE')
            if inbox.get('status') != 'PROCESSED':
                continue
            require(any(d.get('outcome') == 'NEW' for d in inbox.get('deliveries', [])), 'NO_ACCEPTED_DELIVERY')
            evidence.append({'charge': charge, 'status': 'CAPTURED', 'event_id': event['id'],
                             'inbox_status': inbox['status'], 'api_version': inbox['apiVersion'],
                             'relay_body_sha256': row['sha256'], 'signature_evidence': 'Stripe listen -> relay -> inbox'})
            pending.remove(charge)
            if first_body is None:
                first_body = body
        report['captured_count'] = len(evidence)
        report['missing_charges'] = sorted(pending)
        if pending:
            emit({'waiting': len(pending), 'captured': len(evidence)})
            sleep(min(2, remaining()))
    return first_body


def accept():
    report = {'ac': 'AC-8.4', 'started_at': instant(), 'result': 'FAIL', 'captured_count': 0,
              'negative_signature_source': 'script HMAC with local signing secret, not Stripe original signature',
              'real_signature_evidence': 'each real event passed app signature verification before inbox insertion'}
    try:
        run = load_run()
        report['run_id'] = run['run_id']
        rows = current_payments(run)
        report['generated_count'] = len(rows)
        charges = successful_charges(rows)
        body = wait_for_captured(run, charges, report)
        require(body is not None, 'NO_RECORDED_CHARGE')
        checks = []
        report['negative_signatures'] = checks
        for name, payload, header in negative_cases(body, load_secret(), int(utcnow().timestamp())):
            headers = {'Content-Type': 'application/json'}
            if header is not None:
                headers['Stripe-Signature'] = header
            status, _ = request(APP, '/webhooks/stripe', 'POST', payload, headers)
            checks.append({'case': name, 'http_status': status, 'passed': status == 401})
        require(all(case['passed'] for case in checks), 'SIGNATURE_REJECTION_FAILED')
        audit = get_json(APP, '/api/audit/invariants')
        require(isinstance(audit.get('violations'), list) and audit.get('checkedAt'), 'INVALID_AUDIT_RESULT')
        # Store only the narrow audit contract, never arbitrary upstream bodies.
        report['invariants'] = {'checkedAt': audit['checkedAt'], 'violations': audit['violations']}
        require(not audit['violations'], 'INVARIANT_VIOLATIONS')
        report['result'] = 'PASS'
    except Failure as error:
        report['reason'] = str(error)
    except (ValueError, KeyError, TypeError, OSError):
        report['reason'] = 'INVALID_RESPONSE_OR_LOCAL_IO'
    finally:
        report['finished_at'] = instant()
        path = OUT / ('accept-' + utcnow().strftime('%Y%m%dT%H%M%S%fZ') + '.json')
        # Report text is screened before persistence, including unexpected audit detail.
        write_json(path, json.loads(safe(json.dumps(report, ensure_ascii=False))))
        emit({'result': report['result'], 'ac': report['ac'], 'captured': report['captured_count'],
              'report': path.relative_to(ROOT).as_posix(),
              'reason': report.get('reason')})
    return 0 if report['result'] == 'PASS' else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    generate = commands.add_parser('traffic')
    generate.add_argument('--count', type=int, required=True)
    generate.add_argument('--card', choices=['pm_card_bypassPending', 'pm_card_ca'], required=True)
    generate.add_argument('--seed', type=int, required=True)
    check = commands.add_parser('accept')
    check.add_argument('--only', choices=['ac-8.4'], default='ac-8.4')
    args = parser.parse_args()
    try:
        if args.command == 'traffic':
            traffic(args)
            return 0
        return accept()
    except Failure as error:
        emit({'result': 'FAIL', 'reason': str(error)})
        return error.exit_code
    except (OSError, ValueError, KeyError, TypeError):
        emit({'result': 'FAIL', 'reason': 'INVALID_RESPONSE_OR_LOCAL_IO'})
        return 1


if __name__ == '__main__':
    sys.exit(main())
