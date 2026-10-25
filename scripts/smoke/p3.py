"""T14: real outbound HTTP, duplicate relay evidence, pending execution and reconciliation."""
import json
import os
import re
import subprocess
from datetime import timedelta

import p2
from common import APP, SIM, OUT, PROJECT, request, save, check, wait_for, instant, advance_to


def refund_detail(refund_id, status):
    return wait_for(refund_id + ' ' + status,
                    lambda: request(APP, '/api/refunds/' + refund_id),
                    lambda d: d['refund']['status'] == status
                    and len(d['commands']) == 1 and d['commands'][0]['status'] == 'DONE'
                    and len(d['attempts']) == 1)


def check_entries(detail, amount, executed=True):
    types = ['REFUND_ACCEPTED', 'REFUND_EXECUTED'] if executed else ['REFUND_ACCEPTED']
    check([e['entryType'] for e in detail['entries']] == types,
          f'Expected exactly one entry per refund fact: {detail}')
    check(all(e['postingCount'] == 2 for e in detail['entries']), 'Refund entry must have 2 postings')
    check(len(detail['postings']) == 2 * len(types), 'Unexpected refund posting count')
    check(all(p['accountCode'] != 1103 and p['amount'] == amount for p in detail['postings']),
          'Refund must use the requested amount and never touch bank cash')
    check(detail['commands'][0]['first_sent_at'] is not None, 'Command was never sent')
    check(detail['attempts'][0]['response_status'] == 200, 'Expected successful outbound HTTP')


def refund_event(ref, event_type, status, copies):
    # Observer-only SQL discovers event IDs which the refund REST response does not expose.
    # This harness uses the simulator role, never app/readonly and never sim_truth.
    check(re.fullmatch(r're_[A-Za-z0-9_]+', ref) is not None, 'Invalid simulator refund ID')
    check(event_type in ('refund.created', 'refund.updated'), 'Unexpected event type')
    command = ['docker', 'compose', '--env-file', 'compose.smoke.env', '-f', 'compose.yaml',
               '-p', PROJECT, '--profile', 'smoke', 'exec', '-T',
               '-e', 'PGPASSWORD=' + os.environ['LEDGERLAB_SIMULATOR_PASSWORD'],
               'postgres', 'psql', '-h', '127.0.0.1', '-U', 'simulator', '-d', 'ledgerlab',
               '-XAt', '-v', 'ON_ERROR_STOP=1', '-c',
               f"SELECT id FROM sim_processor.event WHERE refund_id='{ref}' AND type='{event_type}'"]
    ids = subprocess.check_output(command, text=True, timeout=20).splitlines()
    check(len(ids) == 1, f'Expected one {event_type} for {ref}: {ids}')
    event_id = ids[0]
    event = wait_for(event_type + ' processed', lambda: request(APP, '/api/events/' + event_id),
                     lambda e: e['status'] == 'PROCESSED' and len(e['deliveries']) == copies)
    check(sorted(d['outcome'] for d in event['deliveries']) ==
          sorted(['NEW'] + ['DUPLICATE'] * (copies - 1)), 'Unexpected webhook deliveries')
    external = request(SIM, '/sim/events/' + event_id)
    check(len(external['deliveries']) == 1 and
          external['deliveries'][0]['target_url'] == 'http://localhost:8081/relay/stripe' and
          external['deliveries'][0]['http_status'] == 200, 'Refund webhook must pass through relay')
    payload = json.loads(external['payload'])
    check(payload['type'] == event_type and payload['data']['object']['id'] == ref
          and payload['data']['object']['status'] == status, 'Wrong refund event identity/status')
    save(ref + '-' + event_type, {'app': event, 'simulator': external})


def charge(name):
    created = save(name, request(SIM, '/sim/charges', 'POST', {'amount': 5000}))
    ref = created['charge']['id']
    wait_for(name + ' captured', lambda: request(APP, '/api/payments/' + ref),
             lambda p: p['status'] == 'CAPTURED')
    return ref


def accept(name, payment, amount):
    body = {'payment_ref': payment, 'merchant_refund_ref': 'smoke-p3-' + name, 'amount': amount}
    headers = {'Idempotency-Key': 'smoke-p3-' + name}
    result = save(name + '-accepted', request(APP, '/api/refunds', 'POST', body,
                                             headers=headers, expected_status=201))
    check(result['status'] == 'READY', f'{name} must pass the merchant funds gate')
    return result, body, headers


def settle(name, expected_net):
    payout = save(name + '-payout', request(SIM, '/sim/payouts', 'POST', {}))
    ref = payout['payout']['id']
    check(payout['payout']['amount'] == expected_net, 'Wrong processor payout net')
    wait_for(name + ' announced', lambda: request(APP, '/api/batches/' + ref),
             lambda b: b['batch']['status'] == 'ANNOUNCED')
    request(SIM, '/sim/payouts/' + ref + '/reconcile', 'POST', {})
    detail = save(name + '-batch', wait_for(name + ' confirmed',
                  lambda: request(APP, '/api/batches/' + ref),
                  lambda b: b['batch']['status'] == 'CONFIRMED'))
    check(detail['batch']['net'] == expected_net, 'Wrong local batch net')
    return ref, detail


def pay(ref):
    paid = request(SIM, '/sim/payouts/' + ref + '/paid', 'POST', {})
    wait_for('paid event', lambda: request(APP, '/api/events/' + paid['event_id']),
             lambda e: e['status'] == 'PROCESSED')


def run():
    cutoff = '2026-10-24T04:00:00Z'
    check(instant(request(APP, '/api/overview')['asOf']['at']) >= instant(cutoff),
          'P3 smoke host clock must be after 2026-10-24T04:00:00Z')
    p1 = p2.run()
    p2_ref = charge('p3-charge-p2')
    plan = {'rules': [{'event_type': 'refund.created', 'action': 'DUPLICATE', 'copies': 2}]}
    check(request(SIM, '/sim/relay/plan', 'PUT', plan) == plan, 'Refund DUPLICATE plan not installed')
    # Sample A step 5 refunds P1; P2 supplies the processor's available funds.
    r1, body, headers = accept('r1', p1, 2000)
    r1_id = r1['refund_id']
    detail = refund_detail(r1_id, 'SUCCEEDED')
    r1_ref = detail['refund']['processor_refund_ref']
    refund_event(r1_ref, 'refund.created', 'succeeded', 2)
    detail = save('r1-executed', refund_detail(r1_id, 'SUCCEEDED'))
    check_entries(detail, 2000)
    replay = save('r1-replayed', request(APP, '/api/refunds', 'POST', body,
                                        headers=headers, expected_status=201))
    check(replay == r1, 'Idempotent replay must return the entire original response')
    check(len(request(APP, '/api/payments/' + p1)['refunds']) == 1, 'Replay created another refund')
    overview = request(APP, '/api/overview')
    check((overview['merchantAvailable'], overview['refund_payable'], overview['bankCash']) ==
          (7680, 0, 9680), 'Wrong balances after R1 execution')
    check(next(a['balance'] for a in overview['accounts'] if a['code'] == 1101) == 3000,
          'R1 must reduce processor receivable to 3000')
    print('PASS AC-2.9/2.10/3.1/3.14: outbound HTTP 200; refund.created NEW + DUPLICATE; '
          'R1 SUCCEEDED, 1 REFUND_EXECUTED; same-key replay identical')
    request(SIM, '/sim/relay/plan', 'PUT', {'rules': []})

    b2, batch = settle('b2', 2825)
    check((batch['batch']['gross'], batch['batch']['fees'], batch['batch']['refunds']) ==
          (5000, 175, 2000), 'Wrong B2 components')
    check(sorted((line['lineType'], line['processorRef'], line['gross']) for line in batch['lines']) ==
          [('PAYMENT', p2_ref, 5000), ('REFUND', r1_ref, -2000)], 'Wrong B2 payment/refund lines')
    overview = save('sample-a-step-7', request(APP, '/api/overview'))
    check((overview['merchantAvailable'], overview['inTransit'], overview['bankCash']) ==
          (12505, 2825, 9680), 'Balances differ from sample A step 7')
    check_entries(refund_detail(r1_id, 'SUCCEEDED'), 2000)
    print('PASS AC-3.1: B2 gross=5000, fees=175, refund=-2000; '
          'merchantAvailable=12505, inTransit=2825; execution still unique')
    pay(b2)

    # Keep the second refund pending even after fresh processor funding arrives.
    request(SIM, '/sim/refunds/faults', 'POST', {'charge': p2_ref, 'force_pending': True})
    r2, _, _ = accept('r2', p2_ref, 3000)
    r2_id = r2['refund_id']
    pending = save('r2-submitted', refund_detail(r2_id, 'SUBMITTED'))
    check_entries(pending, 3000, executed=False)
    refund_event(pending['refund']['processor_refund_ref'], 'refund.created', 'pending', 1)
    check(request(APP, '/api/overview')['refund_payable'] == 3000, 'Pending liability must remain')
    charge('p3-charge-p3')
    check(request(APP, '/api/refunds/' + r2_id)['refund']['status'] == 'SUBMITTED',
          'force_pending must hold R2 despite processor funds')
    request(SIM, '/sim/refunds/faults', 'POST', {'charge': p2_ref})
    processed = request(SIM, '/sim/refunds/process', 'POST', {})
    check(processed['events'] == 1, 'Process must emit one refund.updated')
    succeeded = save('r2-executed', refund_detail(r2_id, 'SUCCEEDED'))
    check_entries(succeeded, 3000)
    r2_ref = succeeded['refund']['processor_refund_ref']
    refund_event(r2_ref, 'refund.updated', 'succeeded', 1)
    success = [h for h in succeeded['history'] if h['to_status'] == 'SUCCEEDED']
    check(len(success) == 1 and success[0]['evidence_source'] == 'WEBHOOK'
          and instant(success[0]['occurred_at']) < instant(cutoff),
          'R2 execution must be in the reconciliation cutoff, not silently excluded')
    print('PASS AC-3.18/3.14: R2 SUBMITTED, force_pending holds after funding; '
          'process -> refund.updated -> SUCCEEDED, 1 REFUND_EXECUTED')

    # Complete external evidence so ALL has no legitimate missing-bank/statement findings.
    b3, batch = settle('b3', 1825)
    check(any(line['processorRef'] == r2_ref and line['gross'] == -3000
              for line in batch['lines']), 'B3 must contain R2')
    pay(b3)
    advance_to('2026-10-23T12:00:00Z')
    snapshot = save('p3-processor-snapshot', request(APP, '/api/recon/processor-snapshots', 'POST',
                                                   {'businessDate': '2026-10-22'}))
    check(snapshot['status'] == 'ACCEPTED', f'P3 snapshot rejected: {snapshot}')
    statement = request(SIM, '/sim/bank/statements/2026-10-23', csv=True)
    (OUT / 'p3-bank.csv').write_bytes(statement)
    imported = save('p3-bank-import', request(APP, '/api/bank-statements', 'POST', statement, csv=True))
    check(imported['status'] == 'ACCEPTED', f'P3 bank import rejected: {imported}')
    for ref in (b2, b3):
        check(request(APP, '/api/batches/' + ref)['batch']['status'] == 'BANKED', 'Batch not banked')
    before = [save(name + '-final', refund_detail(ref, 'SUCCEEDED'))
              for name, ref in (('r1', r1_id), ('r2', r2_id))]
    overview = save('p3-overview', request(APP, '/api/overview'))
    check((overview['merchantAvailable'], overview['bankCash'], overview['inTransit'],
           overview['refund_payable']) == (14330, 14330, 0, 0), 'Wrong final balances')
    evidence = (instant(overview['asOf']['at']).replace(microsecond=0) + timedelta(seconds=1)).isoformat()
    print('Waiting for the app Clock to satisfy the default 60s evidence grace (P3)...')
    wait_for('P3 evidence grace', lambda: request(APP, '/api/overview'),
             lambda o: instant(o['asOf']['at']) >= instant(evidence) + timedelta(seconds=60))
    report = save('p3-reconciliation', request(APP, '/api/recon/runs', 'POST',
                  {'kind': 'ALL', 'cutoffAt': cutoff, 'evidenceAsOf': evidence, 'dryRun': False}))
    check(len(set(report['runIds'])) == 3, 'Expected three persisted reconciliation runs')
    check({r['kind'] for r in report['runs']} == {'PROCESSOR', 'BANK', 'AGING'}, 'Missing rule kind')
    for run in report['runs']:
        check(run['status'] == 'FINISHED' and run['findings'] == [], f'Unexpected findings: {run}')
        check(save('p3-run-' + run['kind'].lower(), request(APP, '/api/recon/runs/' + run['id'])) == run,
              'Persisted P3 report differs')
        if run['kind'] in ('PROCESSOR', 'BANK'):
            file_id = snapshot['fileId'] if run['kind'] == 'PROCESSOR' else imported['fileId']
            check(file_id in run['inputHashes'], 'P3 evidence missing from reconciliation inputs')
    check(save('p3-issues', request(APP, '/api/recon/issues'))['issues'] == [], 'Unexpected P3 issues')
    for prior in before:
        after = refund_detail(prior['refund']['refund_id'], 'SUCCEEDED')
        check_entries(after, prior['refund']['amount'])
        check(after['entries'] == prior['entries'] and after['postings'] == prior['postings'],
              'Reconciliation changed the refund ledger')
    check(request(APP, '/api/overview')['accounts'] == overview['accounts'], 'Reconciliation changed balances')
    print('PASS AC-5.14/5.8: refund snapshots included; PROCESSOR/BANK/AGING FINISHED, '
          '0 findings, 0 issues; ledger unchanged')
    print('PASS T14: S3 -> refund command -> relay duplicate -> B2 -> pending/process -> ALL')


if __name__ == '__main__':
    run()
