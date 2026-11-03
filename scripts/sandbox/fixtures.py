"""Export S1 from the simulator's redacted recordings; no raw bodies reach disk."""
import argparse
import json
import re
import sys
from datetime import datetime, timezone
from urllib.parse import urlencode

from probe import VERSION
from runtime import ROOT, SIM, Failure, emit, get_json, load_run, require, write_json

UNSAFE = re.compile(r'sk_|rk_|whsec_|_secret_|@|acct_(?!REDACTED(?![A-Za-z0-9_]))', re.I)


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
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenario', choices=['s1'], required=True)
    parser.parse_args()
    try:
        run = load_run()
        summary = get_json(SIM, '/sim/sandbox/summary')
        require(summary['run_id'] == run['run_id'], 'RUN_MISMATCH')
        payments = get_json(SIM, '/sim/sandbox/payments')
        charges = {p['charge'] for p in payments if p.get('status') == 'succeeded'}
        webhook = select_s1(recordings('webhook', event_type='charge.succeeded'), run['run_id'], charges)
        relative = 'src/test/resources/contracts/stripe-sandbox/s1-payment'
        manifest = export_s1(run, webhook, recordings('api', path_prefix='/v1/balance_transactions'),
                             ROOT / relative, ROOT / 'simulator' / relative)
        emit({'result': 'EXPORTED', 'scenario': 's1', 'files': len(manifest['files']),
              'api_recording': manifest['api_recording']})
        return 0
    except Failure as failure:
        emit({'result': 'FAIL', 'reason': str(failure)})
        return 2
    except (ValueError, KeyError, TypeError, OSError):
        emit({'result': 'FAIL', 'reason': 'FIXTURE_EXPORT_FAILED'})
        return 2


if __name__ == '__main__':
    sys.exit(main())
