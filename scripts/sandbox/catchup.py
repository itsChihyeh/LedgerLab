"""AC-8.5: complete Events enumeration before redelivery, then import bank evidence."""
import argparse
import json
import os
import re
import sys
import time
from datetime import date, datetime, timedelta
from urllib.parse import urlencode

from probe import VERSION
from runtime import (APP, SIM, OUT, ROOT, STATE, EVENTS, Failure, cli, cli_json, emit,
                     get_json, instant, load_run, request, require, safe, simulator_sql,
                     utcnow, validate_run, write_json)


def collect_events(fetch, start, end):
    """No caller sees partial results; cursor loops/malformed pages fail closed."""
    events, cursors = {}, set()
    cursor = None
    pages = 0
    while True:
        page = fetch(cursor)
        require(isinstance(page, dict) and isinstance(page.get('data'), list)
                and type(page.get('has_more')) is bool, 'INVALID_EVENTS_PAGE')
        pages += 1
        for event in page['data']:
            require(isinstance(event, dict) and re.fullmatch(r'evt_[A-Za-z0-9]+', event.get('id', ''))
                    and event.get('livemode') is False and event.get('type') in EVENTS.split(',')
                    and type(event.get('created')) is int and start <= event['created'] < end,
                    'INVALID_LISTED_EVENT')
            item = {key: event[key] for key in ('id', 'type', 'created')}
            require(item['id'] not in events or events[item['id']] == item, 'CONFLICTING_EVENTS_PAGE')
            events[item['id']] = item
        if not page['has_more']:
            return list(events.values()), pages
        require(bool(page['data']), 'EMPTY_NONFINAL_EVENTS_PAGE')
        cursor = page['data'][-1]['id']
        require(cursor not in cursors, 'EVENTS_CURSOR_LOOP')
        cursors.add(cursor)


def list_events(run, now):
    start = int(datetime.fromisoformat(run['window_start'].replace('Z', '+00:00')).timestamp())
    end = int(now.timestamp())
    require(start < end, 'INVALID_EVENT_WINDOW')
    # Stripe retains Events for 30 days. Never call a truncated window complete.
    require(now - datetime.fromtimestamp(start, now.tzinfo) <= timedelta(days=30),
            'EVENT_WINDOW_EXCEEDS_STRIPE_30_DAY_RETENTION')
    key = os.environ.get('STRIPE_API_KEY', '')
    require(re.fullmatch(r'(rk|sk)_test_[A-Za-z0-9_]+', key), 'INVALID_TEST_KEY')

    def fetch(cursor):
        params = [('created[gte]', start), ('created[lt]', end), ('limit', 100)]
        params.extend(('types[]', kind) for kind in EVENTS.split(','))
        if cursor:
            params.append(('starting_after', cursor))
        status, body = request(SIM, '/relay/stripe-api/v1/events?' + urlencode(params),
                               headers={'Authorization': 'Bearer ' + key, 'Stripe-Version': VERSION}, timeout=70)
        require(status == 200, 'EVENTS_PAGE_HTTP_FAILED')
        return json.loads(body)

    return collect_events(fetch, start, end)


def missing_events(events, lookup):
    missing = []
    for event in events:
        inbox = lookup(event['id'])
        if inbox is None:
            missing.append(event)
        else:
            require(inbox.get('eventId') == event['id'], 'INBOX_ID_MISMATCH')
    return missing


def lookup_event(event_id):
    return get_json(APP, '/api/events/' + event_id, missing_ok=True)


def resend(event_id):
    require(re.fullmatch(r'evt_[A-Za-z0-9]+', event_id), 'INVALID_RESEND_ID')
    result = cli(['events', 'resend', event_id])
    value = cli_json(result.stdout)
    require(result.returncode == 0 and not value.get('error') and value.get('id') == event_id
            and value.get('livemode') is False, 'STRIPE_RESEND_NOT_ACCEPTED')


def await_missing(events, seconds=60, monotonic=time.monotonic, sleep=time.sleep):
    deadline = monotonic() + seconds
    while True:
        missing = missing_events(events, lookup_event)
        remaining = deadline - monotonic()
        if not missing or remaining <= 0:
            return missing
        sleep(min(2, remaining))


def bank_dates(lines, today):
    # Include visible_date: PENDING_FIRST may become visible before booking_date.
    first = min([today] + [date.fromisoformat(row['visible_date']) for row in lines])
    return [(first + timedelta(days=i)).isoformat() for i in range((today - first).days + 1)]


def sync_bank(run, report):
    result = get_json(SIM, '/sim/sandbox/bank/sync', 'POST', {})
    report['bank_sync'] = {key: result[key] for key in ('seen', 'received', 'pages', 'httpStatus', 'errorCode')}
    require(result['httpStatus'] == 200 and not result['errorCode'], 'BANK_SYNC_FAILED')
    now = datetime.fromisoformat(get_json(SIM, '/sim/clock')['now'].replace('Z', '+00:00'))
    require(now.tzinfo is not None, 'INVALID_SIM_CLOCK')
    # PostgreSQL's IANA data avoids adding tzdata on Windows. Observer role, no app DB access.
    today = date.fromisoformat(simulator_sql(run, "SELECT to_json(('" + instant(now)
                                             + "'::timestamptz AT TIME ZONE 'America/Toronto')::date);"))
    lines = get_json(SIM, '/sim/bank/transactions')
    imports = report['bank_imports'] = []
    for day in bank_dates(lines, today):
        status, csv = request(SIM, '/sim/bank/statements/' + day)
        require(status == 200, 'BANK_CSV_EXPORT_FAILED')
        status, body = request(APP, '/api/bank-statements', 'POST', csv,
                               {'Content-Type': 'text/csv', 'X-Source-Name': 'sandbox-' + day + '.csv'})
        require(status == 200, 'BANK_CSV_IMPORT_FAILED')
        result = json.loads(body)
        imports.append({'date': day, 'file_id': result['fileId'], 'status': result['status']})
        require(result['status'] == 'ACCEPTED', 'BANK_CSV_REJECTED')


def catchup(window_start=None):
    report = {'ac': 'AC-8.5', 'started_at': instant(), 'result': 'FAIL', 'complete': False,
              'resend_count': 0, 'resend_failures': [], 'missing_count': None, 'wait_seconds': 60}
    code = 1
    try:
        run = load_run()
        if window_start:
            run = validate_run(dict(run, window_start=window_start))
        report.update(run_id=run['run_id'], window_start=run['window_start'])
        try:
            events, pages = list_events(run, utcnow())
        except (Failure, ValueError, KeyError, TypeError):
            raise Failure('不完整：Events 分页或窗口校验失败；未重投', 2) from None
        report.update(complete=True, pages=pages, event_count=len(events))
        missing = missing_events(events, lookup_event)
        report['initial_missing_count'] = len(missing)
        for event in missing:
            report['resend_count'] += 1
            try:
                resend(event['id'])
            except Failure as error:
                report['resend_failures'].append({'event_id': event['id'], 'reason': str(error)})
        missing = await_missing(missing)
        report.update(missing_count=len(missing), missing_events=missing)
        write_json(STATE / 'missing-events.json', {'run_id': run['run_id'], 'window_start': run['window_start'],
                                                  'checked_at': instant(), 'events': missing})
        sync_bank(run, report)
        report['result'] = 'PASS' if not missing and not report['resend_failures'] else 'MISSING'
        code = 0 if report['result'] == 'PASS' else 1
    except Failure as error:
        report['reason'], code = str(error), error.exit_code
    except (OSError, ValueError, KeyError, TypeError):
        report['reason'] = 'INVALID_RESPONSE_OR_LOCAL_IO'
    finally:
        report['finished_at'] = instant()
        path = OUT / ('catchup-' + utcnow().strftime('%Y%m%dT%H%M%S%fZ') + '.json')
        write_json(path, json.loads(safe(json.dumps(report, ensure_ascii=False))))
        emit(dict(report, report=path.relative_to(ROOT).as_posix()))
    return code


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--window-start')
    sys.exit(catchup(parser.parse_args().window_start))
