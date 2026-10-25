import json
import sys
import time
from datetime import datetime
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, ProxyHandler, build_opener

APP, SIM = ('http://' + address.strip() for address in sys.argv[1:3])
PROJECT = sys.argv[3]
OUT = Path(sys.argv[4])
http = build_opener(ProxyHandler({}))


def request(base, path, method='GET', data=None, csv=False, headers=None, expected_status=200):
    body = data if isinstance(data, bytes) else (
        json.dumps(data).encode() if data is not None else None)
    headers = {'Content-Type': 'text/csv' if csv else 'application/json', **(headers or {})}
    try:
        with http.open(Request(base + path, body, headers, method=method), timeout=20) as response:
            check(response.status == expected_status,
                  f'{method} {path}: expected HTTP {expected_status}, got {response.status}')
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
