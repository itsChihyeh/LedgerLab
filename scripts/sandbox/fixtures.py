"""Export S1-S4 from the simulator's redacted recordings; no raw bodies reach disk."""
import argparse
import json
import re
import sys
from datetime import datetime, timezone
from urllib.parse import urlencode

from probe import VERSION
from runtime import ROOT, SIM, Failure, emit, get_json, load_run, require, write_json

UNSAFE = re.compile(r'sk_|rk_|whsec_|_secret_|@|acct_(?!REDACTED(?![A-Za-z0-9_]))', re.I)
BASE = 'src/test/resources/contracts/stripe-sandbox/'
DIRECTORIES = {'s1': 's1-payment', 's2': 's2-refund', 's3': 's3-refund-pending', 's4': 's4-payout'}


def scan(value):
    require(not UNSAFE.search(json.dumps(value, ensure_ascii=False)), 'UNSAFE_FIXTURE_EXPORT')


def iso(seconds):
    require(type(seconds) is int, 'INVALID_TIMESTAMP')
    return datetime.fromtimestamp(seconds, timezone.utc).isoformat().replace('+00:00', 'Z')


def recordings(kind, **filters):
    after = 0
    while True:
        page = get_json(SIM, '/sim/sandbox/recordings?' + urlencode(
            dict(kind=kind, limit=100, after_id=after, **filters)))
        require(isinstance(page, list), 'INVALID_RECORDING_PAGE')
        scan(page)
        for row in page:
            require(type(row.get('id')) is int and row['id'] > after, 'INVALID_RECORDING_CURSOR')
            after = row['id']
            yield row
        if len(page) < 100:
            return


def select_s1(rows, run_id, charges):
    for row in rows:
        event = row['body']
        charge = event.get('data', {}).get('object', {})
        if (event.get('type') == 'charge.succeeded' and charge.get('id') in charges
                and charge.get('metadata', {}).get('ledgerlab_run_id') == run_id):
            require(event.get('livemode') is False and charge.get('livemode') is False, 'LIVE_EVENT')
            require(event.get('api_version') == VERSION, 'API_VERSION_MISMATCH')
            return row
    raise Failure('S1_RECORDING_MISSING')


def webhook_expected(event, recorded_at):
    c = event['data']['object']
    return {'event': {'id': event['id'], 'type': event['type'], 'apiVersion': event['api_version'],
                      'livemode': event['livemode'], 'occurredAt': iso(event['created'])},
            'charge': {'eventId': event['id'], 'chargeId': c['id'], 'paymentIntentId': c['payment_intent'],
                       'amount': c['amount'], 'amountCaptured': c['amount_captured'], 'currency': c['currency'],
                       'captured': c['captured'], 'status': c['status'], 'livemode': c['livemode'],
                       'created': iso(c['created']), 'receivedAt': recorded_at},
            'admission': 'APPLIED'}


def balance_expected(item):
    return {**{k: item[k] for k in ('id', 'type', 'source', 'amount', 'fee', 'net', 'currency')},
            'created': iso(item['created'])}


def write_scenario(files, destination, mirror):
    # Validate everything BEFORE either directory is touched, including stale-file removal.
    for content in files.values():
        scan(content)
    for directory in (destination, mirror):
        directory.mkdir(parents=True, exist_ok=True)
        for filename, content in files.items():
            write_json(directory / filename, content)
        for stale in directory.glob('*.json'):
            if stale.name not in files:
                stale.unlink()


def export_s1(run, webhook, api_rows, destination, mirror):
    event = webhook['body']
    charge = event['data']['object']
    files = {'01-webhook-charge.succeeded.json': event}
    entries = [{'file': next(iter(files)), 'order': 1, 'kind': 'webhook', 'type': event['type'],
                'api_version': event['api_version'], 'recorded_at': webhook['recorded_at'],
                'expected': webhook_expected(event, webhook['recorded_at'])}]
    for row in api_rows:
        if (row.get('method') != 'GET' or row.get('path') != '/v1/balance_transactions'
                or row.get('status') != 200):
            continue
        page = row['body']
        if not any(x.get('source') == charge['id'] for x in page.get('data', [])):
            continue
        # Preserve the entire recorded response, including pagination. S1 requires a complete page.
        if page.get('has_more') is not False:
            continue
        filename = '02-api-balance_transactions.json'
        files[filename] = page
        entries.append({'file': filename, 'order': 2, 'kind': 'api', 'type': 'balance_transaction',
                        'method': 'GET', 'path': row['path'], 'status': row['status'],
                        'api_version': VERSION, 'recorded_at': row['recorded_at'],
                        'expected': {'items': [balance_expected(x) for x in page['data']]}})
        break
    manifest = {'scenario': 's1', 'run_id': run['run_id'], 'api_version': VERSION,
                'recorded_at': webhook['recorded_at'], 'files': entries,
                'api_recording': 'included' if len(entries) == 2 else 'not_available'}
    files['manifest.json'] = manifest
    write_scenario(files, destination, mirror)
    return manifest


def refund_expected(refund):
    metadata = refund.get('metadata') or {}
    return {'ref': refund['id'], 'refundId': metadata.get('ledgerlab_refund_id'),
            'chargeRef': refund['charge'], 'amount': refund['amount'], 'status': refund['status'],
            'failureReason': refund.get('failure_reason')}


def payout_expected(payout):
    return {'id': payout['id'], 'amount': payout['amount'], 'currency': payout['currency'],
            'automatic': payout['automatic'], 'method': payout['method'], 'status': payout['status'],
            'reconciliationStatus': payout['reconciliation_status'],
            'arrivalDate': iso(payout['arrival_date']), 'created': iso(payout['created'])}


def entry_of(name, order, kind, row, expected, **extra):
    entry = {'file': name, 'order': order, 'kind': kind, 'type': extra.pop('type'),
             'api_version': VERSION, 'recorded_at': row['recorded_at']}
    entry.update(extra)
    entry['expected'] = expected
    return entry


def webhook_entry(name, order, row, subject):
    event = row['body']
    require(event.get('livemode') is False and event.get('api_version') == VERSION, 'LIVE_OR_WRONG_VERSION')
    expected = {'event': {'id': event['id'], 'type': event['type'], 'apiVersion': event['api_version'],
                          'livemode': event['livemode'], 'occurredAt': iso(event['created'])}}
    if subject == 'refund':
        expected['refund'] = {**refund_expected(event['data']['object']), 'occurredAt': iso(event['created'])}
    else:
        expected['payout'] = payout_expected(event['data']['object'])
    return entry_of(name, order, 'webhook', row, expected, type=event['type'])


def masked(event):
    """Event copy without the request's idempotency key: it is our own refund id, no parser reads it,
    and gitleaks' generic rule rightly cannot tell it from a credential."""
    copy = json.loads(json.dumps(event))
    request = copy.get('request')
    if isinstance(request, dict) and isinstance(request.get('idempotency_key'), str):
        request['idempotency_key'] = 'IDEMPOTENCY_KEY_REDACTED'
    return copy


def api_ok(row, method, path_test):
    return (row.get('method') == method and row.get('status') == 200
            and path_test(row.get('path', '')) and isinstance(row.get('body'), dict))


def select_refund(api_rows, webhooks, charges, status):
    """First create in the current run with this status that also has both refund events and a
    complete charge list. None when test mode never produced such a refund."""
    api_rows, webhooks = list(api_rows), list(webhooks)
    for post in api_rows:
        refund = post['body']
        if not api_ok(post, 'POST', lambda x: x == '/v1/refunds') or refund.get('status') != status:
            continue
        if refund.get('charge') not in charges or refund.get('livemode') is True:
            continue
        mine = [w for w in webhooks if w['body']['data']['object'].get('id') == refund['id']]
        created = next((w for w in mine if w['body']['type'] == 'refund.created'), None)
        updated = next((w for w in mine if w['body']['type'] == 'refund.updated'), None)
        listing = next((r for r in api_rows if api_ok(r, 'GET', lambda x: x == '/v1/refunds')
                        and r['body'].get('has_more') is False
                        and any(x.get('id') == refund['id'] for x in r['body'].get('data', []))), None)
        if created and updated and listing:
            retrieve = next((r for r in api_rows
                             if api_ok(r, 'GET', lambda x: x.startswith('/v1/refunds/re_'))
                             and r['body'].get('charge') in charges and r['body'].get('status') == status), None)
            return {'post': post, 'created': created, 'updated': updated, 'list': listing,
                    'retrieve': retrieve}
    return None


def export_refund(scenario, run, bundle, destination, mirror):
    post, listing, retrieve = bundle['post'], bundle['list'], bundle['retrieve']
    refund = post['body']
    files, entries = {}, []

    def add(name, row, expected, **extra):
        files[name] = row['body']
        entries.append(entry_of(name, len(entries) + 1, 'api', row, expected, **extra))

    add('01-api-refunds-create.json', post, {'operation': 'create', 'refund': refund_expected(refund)},
        type='refund', method='POST', path='/v1/refunds', status=200)
    for name, key in (('02-webhook-refund.created.json', 'created'), ('03-webhook-refund.updated.json', 'updated')):
        files[name] = masked(bundle[key]['body'])
        entries.append(webhook_entry(name, len(entries) + 1, bundle[key], 'refund'))
    match = next(x for x in listing['body']['data'] if x['id'] == refund['id'])
    add('04-api-refunds-list.json', listing,
        {'operation': 'findByRefundId', 'refund': refund_expected(match), 'items': len(listing['body']['data'])},
        type='refund', method='GET', path='/v1/refunds', status=200)
    if retrieve is not None:
        add('05-api-refunds-retrieve.json', retrieve,
            {'operation': 'retrieve', 'refund': refund_expected(retrieve['body'])},
            type='refund', method='GET', path='/v1/refunds/' + retrieve['body']['id'], status=200)
    files['manifest.json'] = {'scenario': scenario, 'run_id': run['run_id'], 'api_version': VERSION,
                              'recorded_at': entries[0]['recorded_at'], 'files': entries,
                              'retrieve_recording': 'included' if retrieve is not None else 'not_available'}
    write_scenario(files, destination, mirror)
    return files['manifest.json']


def select_s4(api_rows, webhooks):
    """The earliest complete payout lifecycle: three webhooks, a retrieve and the payout's line page."""
    api_rows, webhooks = list(api_rows), list(webhooks)
    for first in (w for w in webhooks if w['body']['type'] == 'payout.created'):
        payout = first['body']['data']['object']
        mine = {w['body']['type']: w for w in webhooks if w['body']['data']['object'].get('id') == payout['id']}
        if not all(t in mine for t in ('payout.reconciliation_completed', 'payout.paid')):
            continue
        require(payout.get('livemode') is False and first['body'].get('livemode') is False, 'LIVE_EVENT')
        retrieve = next((r for r in api_rows if api_ok(r, 'GET', lambda x: x == '/v1/payouts/' + payout['id'])), None)
        lines = next((r for r in api_rows if api_ok(r, 'GET', lambda x: x == '/v1/balance_transactions')
                      and r['body'].get('has_more') is False
                      and any(x.get('type') == 'payout' and x.get('source') == payout['id']
                              for x in r['body'].get('data', []))), None)
        if retrieve and lines:
            return {'created': first, 'reconciled': mine['payout.reconciliation_completed'],
                    'paid': mine['payout.paid'], 'retrieve': retrieve, 'lines': lines}
    return None


def export_s4(run, bundle, destination, mirror):
    files, entries = {}, []
    payout = bundle['created']['body']['data']['object']
    for name, key in (('01-webhook-payout.created.json', 'created'),
                      ('02-webhook-payout.reconciliation_completed.json', 'reconciled'),
                      ('03-webhook-payout.paid.json', 'paid')):
        files[name] = masked(bundle[key]['body'])
        entries.append(webhook_entry(name, len(entries) + 1, bundle[key], 'payout'))
    row = bundle['retrieve']
    files['04-api-payouts-retrieve.json'] = row['body']
    entries.append(entry_of('04-api-payouts-retrieve.json', 4, 'api', row,
                            {'operation': 'retrieve', 'payout': payout_expected(row['body'])},
                            type='payout', method='GET', path='/v1/payouts/' + payout['id'], status=200))
    page = bundle['lines']
    files['05-api-balance_transactions.json'] = page['body']
    entries.append(entry_of('05-api-balance_transactions.json', 5, 'api', page,
                            {'operation': 'listForPayout', 'payoutId': payout['id'], 'pages': 1,
                             'items': [balance_expected(x) for x in page['body']['data']]},
                            type='balance_transaction', method='GET', path='/v1/balance_transactions', status=200))
    files['manifest.json'] = {'scenario': 's4', 'run_id': run['run_id'], 'api_version': VERSION,
                              'recorded_at': entries[0]['recorded_at'], 'files': entries}
    write_scenario(files, destination, mirror)
    return files['manifest.json']


def export(scenario, run, charges):
    destination = ROOT / BASE / DIRECTORIES[scenario]
    mirror = ROOT / 'simulator' / BASE / DIRECTORIES[scenario]
    if scenario == 's1':
        webhook = select_s1(recordings('webhook', event_type='charge.succeeded'), run['run_id'], charges)
        manifest = export_s1(run, webhook, recordings('api', path_prefix='/v1/balance_transactions'),
                             destination, mirror)
        return {'result': 'EXPORTED', 'scenario': 's1', 'files': len(manifest['files']),
                'api_recording': manifest['api_recording']}
    if scenario in ('s2', 's3'):
        rows = list(recordings('api', path_prefix='/v1/refunds'))
        hooks = [*recordings('webhook', event_type='refund.created'),
                 *recordings('webhook', event_type='refund.updated')]
        bundle = select_refund(rows, hooks, charges, 'succeeded' if scenario == 's2' else 'pending')
        if bundle is None:
            require(scenario == 's3', 'S2_RECORDING_MISSING')
            # Test mode never produced a pending refund; the committed tree stays untouched.
            return {'result': 'NOT_REPRODUCED', 'scenario': 's3',
                    'reason': 'no pending refund was recorded in this run'}
        manifest = export_refund(scenario, run, bundle, destination, mirror)
        return {'result': 'EXPORTED', 'scenario': scenario, 'files': len(manifest['files']),
                'retrieve_recording': manifest['retrieve_recording']}
    hooks = [w for t in ('payout.created', 'payout.reconciliation_completed', 'payout.paid')
             for w in recordings('webhook', event_type=t)]
    bundle = select_s4(list(recordings('api', path_prefix='/v1/payouts'))
                       + list(recordings('api', path_prefix='/v1/balance_transactions')), hooks)
    require(bundle is not None, 'S4_RECORDING_MISSING')
    manifest = export_s4(run, bundle, destination, mirror)
    return {'result': 'EXPORTED', 'scenario': 's4', 'files': len(manifest['files'])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenario', choices=[*DIRECTORIES, 'all'], required=True)
    choice = parser.parse_args().scenario
    scenarios = list(DIRECTORIES) if choice == 'all' else [choice]
    try:
        run = load_run()
        summary = get_json(SIM, '/sim/sandbox/summary')
        require(summary['run_id'] == run['run_id'], 'RUN_MISMATCH')
        payments = get_json(SIM, '/sim/sandbox/payments')
        charges = {p['charge'] for p in payments if p.get('status') == 'succeeded'}
        for scenario in scenarios:
            emit(export(scenario, run, charges))
        return 0
    except Failure as failure:
        emit({'result': 'FAIL', 'reason': str(failure)})
        return 2
    except (ValueError, KeyError, TypeError, OSError):
        emit({'result': 'FAIL', 'reason': 'FIXTURE_EXPORT_FAILED'})
        return 2


if __name__ == '__main__':
    sys.exit(main())
