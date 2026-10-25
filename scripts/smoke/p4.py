"""T08: HTTP-only P4 corrections and bank returns, starting from an empty ledger."""
from datetime import timedelta

from common import APP, SIM, OUT, request, save, check, wait_for, instant, advance_to
from p3 import settle, pay


def balances(name, receivable=0, transit=0, cash=0, pending=0, available=0, unapplied=0):
    overview = save(name + '-balances', request(APP, '/api/overview'))
    expected = {1101: receivable, 1102: transit, 1103: cash, 2101: pending,
                2102: available, 2103: 0, 2104: 0, 2105: unapplied}
    actual = {a['code']: a['balance'] for a in overview['accounts']}
    check(actual == expected, f'{name}: expected {expected}, got {actual}')
    return overview


def payment(ref, status):
    detail = request(APP, '/api/payments/' + ref)
    check(detail['status'] == status, f'{ref}: expected {status}, got {detail}')
    return detail


def charge(name, amount):
    created = save(name, request(SIM, '/sim/charges', 'POST', {'amount': amount}))
    wait_for(name + ' captured', lambda: request(APP, '/api/payments/' + created['charge']['id']),
             lambda p: p['status'] == 'CAPTURED')
    return created


def batch(ref, status):
    detail = request(APP, '/api/batches/' + ref)
    check(detail['batch']['status'] == status, f'{ref}: expected {status}, got {detail}')
    return detail


def bank(ref, status):
    detail = request(APP, '/api/bank-lines/' + ref)
    check(detail['bankLine']['matchStatus'] == status, f'{ref}: expected {status}, got {detail}')
    return detail


def issues():
    return request(APP, '/api/recon/issues?limit=100')['issues']


def resolved(ids, correction_id=None, returned_by=None):
    for issue_id in ids:
        detail = save(issue_id + '-resolved', request(APP, '/api/recon/issues/' + issue_id))
        issue = detail['issue']
        check(issue['status'] == 'RESOLVED', f'Issue was not resolved: {issue}')
        check(issue['resolutionCorrectionId'] == correction_id, 'Wrong resolution correction')
        if returned_by:
            check(returned_by in issue['explanation'], 'Return evidence is missing')


def correct(name, kind, reason, evidence, **parameters):
    body = {'type': kind, 'reasonCode': reason, 'reasonNote': 'T08 synthetic ' + name,
            'requestedBy': 'smoke-p4', 'evidence': evidence, **parameters}
    save(name + '-request', body)
    submitted = request(APP, '/api/corrections', 'POST', body, expected_status=201)
    detail = save(name, request(APP, '/api/corrections/' + submitted['correction']['id']))
    check(detail == submitted, 'Committed correction differs from POST response')
    return detail


def postings(entry, expected):
    actual = sorted((p['accountCode'], p['side'], p['amount']) for p in entry['postings'])
    check(actual == sorted(expected), f'Wrong {entry["entryType"]} postings: {actual}')


def import_bank(name, raw):
    (OUT / (name + '.csv')).write_bytes(raw)
    imported = save(name, request(APP, '/api/bank-statements', 'POST', raw, csv=True))
    check(imported['status'] == 'ACCEPTED', f'Bank statement rejected: {imported}')
    return imported


def run():
    balances('empty')
    # Sample E: retain the original 4500 capture; present an independent processor
    # line for 5400 / fee 187 through the simulator's existing fault API.
    p5 = charge('sample-e-p5', 4500)
    ref = p5['charge']['id']
    original = payment(ref, 'CAPTURED')['postings'][0]['entryId']
    original_detail = request(APP, '/api/entries/' + original)
    balances('sample-e-captured', receivable=4500, pending=4500)
    payout = save('sample-e-b6-created', request(SIM, '/sim/payouts', 'POST', {'amount': 5213}))
    b6 = payout['payout']['id']
    wait_for('B6 announced', lambda: request(APP, '/api/batches/' + b6),
             lambda b: b['batch']['status'] == 'ANNOUNCED')
    request(SIM, '/sim/payouts/' + b6 + '/faults', 'POST', {
        'omit_transaction': p5['charge']['balance_transaction'],
        'extra_line': {'type': 'charge', 'source': ref, 'amount': 5400, 'fee': 187}})
    request(SIM, '/sim/payouts/' + b6 + '/reconcile', 'POST', {})
    held = save('sample-e-b6-held', wait_for('B6 HELD',
                lambda: request(APP, '/api/batches/' + b6),
                lambda b: b['batch']['status'] == 'HELD'))
    check(held['batch']['holdReason'] == 'AMOUNT_MISMATCH' and held['entries'] == [],
          'B6 must be held without settlement postings')
    balances('sample-e-held', receivable=4500, pending=4500)
    mismatch = [i for i in issues() if i['subjectId'] == b6]
    check(len(mismatch) == 1 and mismatch[0]['category'] == 'AMOUNT_MISMATCH'
          and mismatch[0]['status'] == 'OPEN', 'Expected one open B6 amount issue')
    # Fetch the independent statement before correcting so its line is valid evidence.
    snapshot = save('p4-processor-before', request(APP, '/api/recon/processor-snapshots', 'POST',
                                                {'businessDate': '2026-10-19'}))
    check(snapshot['status'] == 'ACCEPTED', 'Processor snapshot rejected')
    line = request(APP, '/api/statement-lines/' + snapshot['fileId'] + '/1')['statementLine']
    check((line['processorRef'], line['gross'], line['fee']) == (ref, 5400, 187),
          'Sample E statement must contain the corrected amount and fee')
    correction = correct('sample-e-correction', 'RESTATE_CAPTURE_AMOUNT', 'CAPTURE_AMOUNT_ERROR',
                         [{'type': 'issue', 'id': mismatch[0]['id']},
                          {'type': 'statement_line', 'id': line['statementLineId']},
                          {'type': 'inbox_event', 'id': p5['event_id']}],
                         originalEntryId=original, newAmount=5400)
    cid = correction['correction']['id']
    check(correction['targets'] == [{'entryId': original, 'role': 'REVERSED'}], 'Wrong E target')
    entries = {e['entryType']: e for e in correction['entries']}
    check(len(correction['entries']) == 2 and set(entries) == {'REVERSAL', 'CORRECTION'},
          'E must have exactly one reversal and one correction')
    postings(entries['REVERSAL'], [(2101, 'D', 4500), (1101, 'C', 4500)])
    postings(entries['CORRECTION'], [(1101, 'D', 5400), (2101, 'C', 5400)])
    after_original = request(APP, '/api/entries/' + original)
    check(after_original['entry'] == original_detail['entry']
          and after_original['postings'] == original_detail['postings']
          and after_original['reversedBy'] == cid, 'Original capture was modified or lost its audit link')
    settled = save('sample-e-payment', payment(ref, 'SETTLED'))
    check((settled['amount'], settled['expectedFee'], settled['actualFee'],
           settled['refundable_remaining']) == (5400, 187, 187, 5400), 'Wrong corrected payment')
    confirmed = save('sample-e-b6-confirmed', batch(b6, 'CONFIRMED'))
    check((confirmed['batch']['gross'], confirmed['batch']['fees'], confirmed['batch']['net']) ==
          (5400, 187, 5213), 'Wrong B6 components')
    check([h['toStatus'] for h in confirmed['history'] if h['evidenceId'] == cid] ==
          ['RECEIVED', 'CONFIRMED'], 'Missing atomic correction/recheck history')
    resolved([mismatch[0]['id']], cid)
    balances('sample-e-confirmed', transit=5213, available=5213)
    pay(b6)
    balances('sample-e-paid-no-cash', transit=5213, available=5213)
    print('PASS AC-1.4d/6.5: sample E 4500 -> 5400, fee=187, B6 CONFIRMED, net=5213; issue RESOLVED')

    # Stage both bank plans on October 19; they execute on October 20. All bank
    # statements and expected amounts remain independent of the app ledger.
    duplicate_payment = charge('duplicate-charge', 10000)['charge']['id']
    balances('duplicate-captured', receivable=10000, pending=10000, transit=5213, available=5213)
    duplicate_batch, _ = settle('duplicate', 9680)
    payment(duplicate_payment, 'SETTLED')
    balances('duplicate-confirmed', transit=14893, available=14893)
    request(SIM, '/sim/bank/plan', 'PUT', {'payout_ref': duplicate_batch, 'fault': 'DUPLICATE'})
    pay(duplicate_batch)
    balances('duplicate-paid-no-cash', transit=14893, available=14893)
    split_payment = charge('split-charge', 10000)['charge']['id']
    balances('split-captured', receivable=10000, pending=10000, transit=14893, available=14893)
    split_batch, _ = settle('split', 9680)
    payment(split_payment, 'SETTLED')
    balances('split-confirmed', transit=24573, available=24573)
    request(SIM, '/sim/bank/plan', 'PUT', {'payout_ref': split_batch, 'fault': 'SPLIT'})
    pay(split_batch)
    balances('split-paid-no-cash', transit=24573, available=24573)

    # Create this last so no later payout consumes the deliberately voided charge.
    voided = charge('void-charge', 10000)
    void_ref = voided['charge']['id']
    void_entry = payment(void_ref, 'CAPTURED')['postings'][0]['entryId']
    before_void = request(APP, '/api/entries/' + void_entry)
    balances('before-void', receivable=10000, pending=10000, transit=24573, available=24573)
    void = correct('void-correction', 'VOID_CAPTURE', 'CAPTURE_RECORDED_IN_ERROR',
                   [{'type': 'inbox_event', 'id': voided['event_id']}], originalEntryId=void_entry)
    check(void['targets'] == [{'entryId': void_entry, 'role': 'REVERSED'}]
          and len(void['entries']) == 1 and void['entries'][0]['entryType'] == 'REVERSAL',
          'VOID must only reverse the capture')
    postings(void['entries'][0], [(2101, 'D', 10000), (1101, 'C', 10000)])
    save('void-payment', payment(void_ref, 'VOIDED'))
    after_void = request(APP, '/api/entries/' + void_entry)
    check(after_void['entry'] == before_void['entry'] and after_void['postings'] == before_void['postings']
          and after_void['reversedBy'] == void['correction']['id'], 'Original void target changed')
    balances('after-void', transit=24573, available=24573)
    print('PASS AC-6.3: unsettled capture VOIDED, one exact REVERSAL, original entry unchanged')

    advance_to('2026-10-20T12:00:00Z')
    bank_file = import_bank('p4-bank-credits', request(SIM, '/sim/bank/statements/2026-10-20', csv=True))
    external = save('p4-bank-transactions', request(SIM, '/sim/bank/transactions'))
    duplicate_lines = [r for r in external if r['reference'] == duplicate_batch]
    split_lines = [r for r in external if r['reference'] == split_batch]
    check(len(duplicate_lines) == 2 and all(r['amount'] == 9680 for r in duplicate_lines),
          'Simulator must actually duplicate the bank transfer')
    check(len(split_lines) == 2 and sorted(r['amount'] for r in split_lines) == [4840, 4840],
          'Simulator must actually split the transfer')
    local_duplicates = [request(APP, '/api/bank-lines/' + r['bank_txn_id']) for r in duplicate_lines]
    check(sorted(d['bankLine']['matchStatus'] for d in local_duplicates) == ['MATCHED', 'UNAPPLIED'],
          'Exactly one duplicate credit must match')
    duplicate_id = next(d['bankLine']['bankTxnId'] for d in local_duplicates
                        if d['bankLine']['matchStatus'] == 'UNAPPLIED')
    split_ids = [r['bank_txn_id'] for r in split_lines]
    for bank_id in split_ids:
        bank(bank_id, 'UNAPPLIED')
    batch(b6, 'BANKED')
    batch(duplicate_batch, 'BANKED')
    batch(split_batch, 'CONFIRMED')
    balances('bank-credits', transit=9680, cash=34253, available=24573, unapplied=19360)
    open_issues = [i for i in issues() if i['status'] == 'OPEN']
    expected = {('UNMATCHED_BANK_LINE', duplicate_id),
                *(('DEPOSIT_AMOUNT_MISMATCH', bank_id) for bank_id in split_ids)}
    check(len(open_issues) == 3 and {(i['category'], i['subjectId']) for i in open_issues} == expected,
          f'Wrong bank issues: {open_issues}')

    # There is no simulator debit control API. Supply independent, synthetic bank
    # evidence via the production CSV importer; do not write either database.
    return_id = 'bt_smoke_p4_return'
    returned_csv = ('H,BANK_STATEMENT,SIM-CAD-001,2026-10-21,2026-10-21,1,CAD\r\n'
                    f'D,{return_id},DEBIT,9680,CAD,BOOKED,{duplicate_id},'
                    '2026-10-21,2026-10-21,Return duplicate processor transfer\r\n'
                    'T,1,0,9680,34253,24573\r\n').encode('utf-8')
    returned_file = import_bank('p4-bank-return', returned_csv)
    bank(duplicate_id, 'RETURNED')
    returned = save('p4-return-detail', bank(return_id, 'RETURN_POSTED'))
    check(len(returned['entries']) == 1 and returned['entries'][0]['entryType'] == 'UNAPPLIED_CASH_RETURNED'
          and returned['entries'][0]['correctionId'] is None
          and returned['entries'][0]['reversesEntryId'] is None, 'Return must be a business entry')
    postings({'entryType': 'UNAPPLIED_CASH_RETURNED', 'postings': returned['postings']},
             [(2105, 'D', 9680), (1103, 'C', 9680)])
    resolved([i['id'] for i in open_issues if i['subjectId'] == duplicate_id], returned_by=return_id)
    balances('bank-returned', transit=9680, cash=24573, available=24573, unapplied=9680)
    print('PASS AC-5.6/6.9: duplicate 9680 UNAPPLIED -> RETURNED, debit RETURN_POSTED; issue RESOLVED')

    applied = correct('split-correction', 'APPLY_UNAPPLIED_CASH', 'SPLIT_DEPOSIT',
                      [{'type': 'bank_txn', 'id': bank_id} for bank_id in split_ids],
                      batchId=split_batch, bankTxnIds=split_ids)
    check(len(applied['entries']) == 1 and applied['entries'][0]['entryType'] == 'CORRECTION',
          'Split application must not create REVERSAL')
    postings(applied['entries'][0], [(2105, 'D', 9680), (1102, 'C', 9680)])
    check(len(applied['targets']) == 2 and all(t['role'] == 'APPLIED' for t in applied['targets']),
          'Both original cash receipts must be APPLIED targets')
    for bank_id in split_ids:
        detail = bank(bank_id, 'APPLIED')
        check(detail['bankLine']['matchedBatchId'] == split_batch, 'Split applied to wrong batch')
        check(detail['entries'][0]['id'] in {t['entryId'] for t in applied['targets']}, 'Wrong applied target')
    save('split-banked', batch(split_batch, 'BANKED'))
    resolved([i['id'] for i in open_issues if i['subjectId'] in split_ids], applied['correction']['id'])
    overview = balances('split-applied', cash=24573, available=24573)
    print('PASS AC-6.10: 4840 + 4840 APPLIED, batch BANKED, one CORRECTION; cash unchanged, issues RESOLVED')

    snapshot = save('p4-processor-final', request(APP, '/api/recon/processor-snapshots', 'POST',
                                               {'businessDate': '2026-10-19'}))
    check(snapshot['status'] == 'ACCEPTED', 'Final processor snapshot rejected')
    before_issues = save('p4-issues-before-recon', issues())
    check(len(before_issues) == 4 and all(i['status'] == 'RESOLVED' for i in before_issues),
          'All four deliberately created issues must already be resolved')
    paths = ['/api/payments/' + r for r in (ref, duplicate_payment, split_payment, void_ref)]
    paths += ['/api/batches/' + r for r in (b6, duplicate_batch, split_batch)]
    paths += ['/api/bank-lines/' + r for r in (duplicate_id, return_id, *split_ids)]
    before = {path: request(APP, path) for path in paths}
    as_of = request(APP, '/api/overview')['asOf']['at']
    evidence = (instant(as_of).replace(microsecond=0) + timedelta(seconds=1)).isoformat()
    print('Waiting for the app Clock to satisfy the default 60s evidence grace (P4)...')
    wait_for('P4 evidence grace', lambda: request(APP, '/api/overview'),
             lambda o: instant(o['asOf']['at']) >= instant(evidence) + timedelta(seconds=60))
    report = save('p4-reconciliation', request(APP, '/api/recon/runs', 'POST', {
        'kind': 'ALL', 'cutoffAt': '2026-10-22T04:00:00Z', 'evidenceAsOf': evidence, 'dryRun': False}))
    check(len(report['runIds']) == 3 and len(set(report['runIds'])) == 3, 'Expected three persisted runs')
    check({r['kind'] for r in report['runs']} == {'PROCESSOR', 'BANK', 'AGING'}, 'Missing rule kind')
    for run in report['runs']:
        check(run['status'] == 'FINISHED' and run['findings'] == [], f'Unexpected P4 findings: {run}')
        check(save('p4-run-' + run['kind'].lower(), request(APP, '/api/recon/runs/' + run['id'])) == run,
              'Persisted report differs')
        inputs = {'PROCESSOR': [snapshot['fileId']], 'BANK': [bank_file['fileId'], returned_file['fileId']]}
        check(all(file_id in run['inputHashes'] for file_id in inputs.get(run['kind'], [])),
              'Final reconciliation omitted scenario evidence')
    after_issues = save('p4-issues-after-recon', issues())
    check({i['id'] for i in after_issues} == {i['id'] for i in before_issues}
          and all(i['status'] == 'RESOLVED' for i in after_issues), 'Reconciliation created or reopened an issue')
    check(request(APP, '/api/overview')['accounts'] == overview['accounts'], 'Reconciliation changed balances')
    for path, prior in before.items():
        after = request(APP, path)
        check({k: v for k, v in after.items() if k != 'asOf'} ==
              {k: v for k, v in prior.items() if k != 'asOf'}, 'Reconciliation mutated ' + path)
    print('PASS AC-5.8/T08: PROCESSOR/BANK/AGING FINISHED, 0 findings, 0 OPEN issues; ledger unchanged')


if __name__ == '__main__':
    run()
