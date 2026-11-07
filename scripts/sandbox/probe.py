"""T01: two test payments, read-only evidence, and a bounded CLI delivery probe.

Run through probe.sh. Raw API bodies, CLI diagnostics and signing secrets are
never printed or persisted. No refunds, account changes, or automatic login.
"""
import json
import os
import re
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener

VERSION = '2026-09-30.endive'
ROOT = Path(__file__).resolve().parents[2]
SENSITIVE = re.compile(r'(?:rk|sk)_(?:test|live)_[A-Za-z0-9_]+|whsec_[A-Za-z0-9_]+|acct_[A-Za-z0-9_]+|[\w.+-]+@[\w.-]+')


class ProbeError(Exception):
    """Fixed categories and allowlisted Stripe error fields, never messages."""


def error_category(status, body):
    category = f'HTTP_{status}'
    try:
        error = json.loads(body).get('error', {})
        # Only constrained machine codes, never Stripe's free-text message.
        for field in ('type', 'code', 'param', 'decline_code'):
            value = error.get(field)
            if isinstance(value, str) and re.fullmatch(r'[a-zA-Z0-9_\[\].]{1,160}', value) and redact(value) == value:
                category += f' {field}={value}'
    except (ValueError, AttributeError):
        pass
    return category


def redact(text):
    return SENSITIVE.sub('[REDACTED]', text)


def emit(label, value):
    print(redact(label + ': ' + json.dumps(value, ensure_ascii=False)), flush=True)


def check(condition, category):
    if not condition:
        raise ProbeError(category)


def integer(value):
    check(type(value) is int and -(2**63) <= value < 2**63, 'INVALID_INTEGER')
    return value


def object_id(value, prefix):
    check(isinstance(value, str) and re.fullmatch(prefix + r'[A-Za-z0-9]+', value), 'INVALID_OBJECT_ID')
    return value


def test_mode(value):
    check(value.get('livemode') is False, 'NOT_TEST_MODE')


def fee_expected(amount):
    return integer(30 + (integer(amount) * 29 + 500) // 1000)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Stripe:
    def __init__(self, key):
        check(re.fullmatch(r'(rk|sk)_test_[A-Za-z0-9_]+', key), 'INVALID_TEST_KEY')
        self.key = key
        self.http = build_opener(NoRedirect())

    def request(self, path, form=None, number=None):
        headers = {'Authorization': 'Bearer ' + self.key, 'Stripe-Version': VERSION}
        body = None
        if form is not None:
            # Slot 1 is retired: Stripe bound it to the rejected old contract.
            # Slot 3 belongs to CLI resend; revised bypass uses fixed slot 4.
            check(number in (2, 4), 'UNAUTHORIZED_WRITE')
            check(path == '/v1/payment_intents', 'UNAUTHORIZED_WRITE')
            headers['Idempotency-Key'] = f'probe-20261102-{number}'
            headers['Content-Type'] = 'application/x-www-form-urlencoded'
            body = urlencode(form).encode()
        try:
            with self.http.open(Request('https://api.stripe.com' + path, body, headers), timeout=30) as response:
                result = json.load(response)
            check(isinstance(result, dict), 'INVALID_RESPONSE')
            if 'livemode' in result:
                test_mode(result)
            return result
        except HTTPError as error:
            # Never include error bodies, URLs, headers or exception reprs.
            raise ProbeError(error_category(error.code, error.read())) from None
        except (URLError, TimeoutError, OSError):
            raise ProbeError('NETWORK_ERROR') from None
        except (ValueError, TypeError):
            raise ProbeError('INVALID_JSON') from None


def balance_summary(balance):
    test_mode(balance)
    return {kind: [{'currency': row['currency'], 'amount': integer(row['amount'])}
                   for row in balance[kind]] for kind in ('available', 'pending')}


class Payments:
    def __init__(self, stripe, state_path, now):
        self.stripe, self.path, self.now = stripe, state_path, now
        self.state = json.loads(state_path.read_text(encoding='utf-8')) if state_path.exists() else {}

    def save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix('.tmp')
        temporary.write_text(json.dumps(self.state, indent=2) + '\n', encoding='utf-8')
        temporary.replace(self.path)

    def get(self, number, amount, card):
        item = self.state.get(str(number))
        if item and item.get('id'):
            pi = self.stripe.request('/v1/payment_intents/' + object_id(item['id'], 'pi_'))
        else:
            now = integer(int(self.now().timestamp()))
            if item:
                check(0 <= now - integer(item['attempted_at']) < 23 * 3600, 'IDEMPOTENCY_WINDOW_EXPIRED_REVIEW_REQUIRED')
            else:
                self.state[str(number)] = {'attempted_at': now}
                self.save()  # Persist uncertainty BEFORE sending a write.
            pi = self.stripe.request('/v1/payment_intents', {
                'amount': amount, 'currency': 'cad', 'payment_method': card,
                'automatic_payment_methods[enabled]': 'true',
                'automatic_payment_methods[allow_redirects]': 'never', 'confirm': 'true',
                'metadata[ledgerlab_run_id]': 'probe',
            }, number)
            self.state[str(number)]['id'] = object_id(pi['id'], 'pi_')
            self.save()
        test_mode(pi)
        check(pi['amount'] == amount and pi['currency'] == 'cad'
              and pi['metadata'].get('ledgerlab_run_id') == 'probe'
              and pi['status'] == 'succeeded', 'UNEXPECTED_PAYMENT')
        charge_id = object_id(pi['latest_charge'], 'ch_')
        charge = self.stripe.request('/v1/charges/' + charge_id)
        test_mode(charge)
        check(charge['amount'] == amount and charge['captured'] is True, 'UNEXPECTED_CHARGE')
        check(charge.get('payment_method_details', {}).get('type') == 'card', 'UNEXPECTED_CHARGE_TYPE')
        # Automatic async capture can briefly leave balance_transaction null.
        for _ in range(10):
            if charge.get('balance_transaction'):
                break
            threading.Event().wait(1)
            charge = self.stripe.request('/v1/charges/' + charge_id)
            test_mode(charge)
        txn_id = object_id(charge.get('balance_transaction'), 'txn_')
        txn = self.stripe.request('/v1/balance_transactions/' + txn_id)
        fee, net = integer(txn['fee']), integer(txn['net'])
        check(integer(txn['amount']) == amount and txn['currency'] == 'cad'
              and txn['source'] == charge_id and amount - fee == net, 'INVALID_BALANCE_TRANSACTION')
        return {'charge': charge_id, 'card': card, 'country': charge['payment_method_details']['card']['country'],
                'amount': amount, 'fee': fee, 'net': net, 'available_on': integer(txn['available_on']),
                'status': txn['status'], 'expected_domestic_fee': fee_expected(amount),
                'fee_matches_domestic': fee == fee_expected(amount), 'created': integer(charge['created'])}


def cli_args(cli, key, args, login=False):
    environment = dict(os.environ)
    environment.pop('STRIPE_API_KEY', None)
    # Default test mode only; login credentials must be set by the user.
    return [cli, *args, *([] if login else ['--api-key', key])], environment


def cli_category(output, returncode):
    lower = output.lower()
    if 'not configured' in lower or 'not authenticated' in lower or 'run `stripe login' in lower or 'run stripe login' in lower:
        return 'LOGIN_REQUIRED'
    if '401' in lower or 'invalid api key' in lower:
        return 'AUTHENTICATION_ERROR'
    if '403' in lower or 'permission' in lower:
        return 'PERMISSION_ERROR'
    if 'webhook' in lower and ('endpoint' in lower or 'not found' in lower):
        return 'WEBHOOK_ENDPOINT_ERROR'
    return 'OK' if returncode == 0 else 'CLI_ERROR'


def run_cli(cli, key, args, login=False, timeout=30):
    command, environment = cli_args(cli, key, args, login)
    try:
        result = subprocess.run(command, env=environment, capture_output=True, timeout=timeout, stdin=subprocess.DEVNULL)
        output = (result.stdout + result.stderr).decode('utf-8', errors='replace')
        return result.returncode, output
    except subprocess.TimeoutExpired:
        return 124, 'TIMEOUT'
    except OSError:
        return 127, 'CLI_UNAVAILABLE'


def cli_probe(cli, key, event_id, login):
    if not cli:
        return {'secret_obtained': False, 'category': 'CLI_UNAVAILABLE', 'resend': 'NOT_TESTED'}
    code, output = run_cli(cli, key, ['listen', '--print-secret'], login)
    secret_ok = code == 0 and re.search(r'whsec_[A-Za-z0-9]+', output) is not None
    result = {'secret_obtained': secret_ok,
              'category': 'OK' if secret_ok else cli_category(output, code),
              'login_mode': login, 'resend': 'NOT_TESTED'}
    del output  # Secret is not persisted, even to ignored files.
    if login and not secret_ok and result['category'] in ('LOGIN_REQUIRED', 'AUTHENTICATION_ERROR'):
        result['action'] = '需要用户 stripe login'
        result['resend'] = '待用户登录后由 T04 观察'
        result['forwarded_api_version'] = '待用户登录后由 T04 观察'
    if not secret_ok or not event_id:
        return result
    received = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            try:
                size = int(self.headers.get('Content-Length', '0'))
                check(0 < size <= 2 * 1024 * 1024, 'INVALID_WEBHOOK_SIZE')
                event = json.loads(self.rfile.read(size))
                test_mode(event)
                received.append({'id': object_id(event['id'], 'evt_'), 'api_version': event.get('api_version'),
                                 'target': event['id'] == event_id})
                self.send_response(200)
            except (ProbeError, ValueError, KeyError, TypeError):
                self.send_response(400)
            self.end_headers()

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    command, environment = cli_args(cli, key, ['listen', '--forward-to',
        f'http://127.0.0.1:{server.server_port}', '--events', 'charge.succeeded'], login)
    ready = threading.Event()
    process = None
    try:
        process = subprocess.Popen(command, env=environment, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)

        def drain():
            for line in iter(process.stdout.readline, b''):
                if b'Ready!' in line or b'whsec_' in line:
                    ready.set()

        reader = threading.Thread(target=drain, daemon=True)
        reader.start()
        if not ready.wait(30):
            result['resend'] = 'LISTENER_NOT_READY'
        else:
            started = time.monotonic()
            code, output = run_cli(cli, key, ['events', 'resend', event_id,
                '--idempotency', 'probe-20261102-3', '--stripe-version', VERSION], login)
            result['resend'] = cli_category(output, code)
            result['resend_exit_code'] = code
            del output
            # The requested observation window, not a concurrency test sleep.
            threading.Event().wait(max(0, 60 - (time.monotonic() - started)))
            result['observation_seconds'] = 60
            result['listener_running_at_end'] = process.poll() is None
    except OSError:
        result['resend'] = 'CLI_UNAVAILABLE'
    finally:
        if process:
            if process.poll() is None:
                process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            if process.stdout:
                process.stdout.close()
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
    result['received'] = received
    result['target_received'] = any(row['target'] for row in received)
    return result


def find_event(stripe, payment):
    path = '/v1/events?' + urlencode({'type': 'charge.succeeded', 'created[gte]': payment['created'], 'limit': 100})
    cursor = ''
    seen = set()
    while True:
        page = stripe.request(path + cursor)
        for event in page['data']:
            test_mode(event)
            if event['data']['object']['id'] == payment['charge']:
                return object_id(event['id'], 'evt_')
        check(type(page['has_more']) is bool, 'INVALID_PAGINATION')
        if not page['has_more']:
            return None
        check(bool(page['data']), 'INVALID_PAGINATION')
        last = object_id(page['data'][-1]['id'], 'evt_')
        check(last not in seen, 'REPEATED_CURSOR')
        seen.add(last)
        cursor = '&starting_after=' + last


def main():
    stripe = Stripe(os.environ.get('STRIPE_API_KEY', ''))
    now = lambda: datetime.now(timezone.utc)
    emit('probe', {'api_version': VERSION, 'observed_at': now().isoformat(), 'refunds_created': 0})
    try:
        emit('balance_before', balance_summary(stripe.request('/v1/balance')))
    except ProbeError as error:
        emit('stopped', {'category': str(error), 'action': '需要用户检查密钥/权限' if str(error).startswith(('HTTP_401', 'HTTP_403')) else '检查连接或测试模式'})
        return 3
    payments = Payments(stripe, ROOT / '.sandbox/probe-state.json', now)
    bypass = None
    payment_error = False
    try:
        bypass = payments.get(4, 1234, 'pm_card_bypassPending')
        emit('payment_bypass', bypass)
        emit('payment_canada', payments.get(2, 10000, 'pm_card_ca'))
    except ProbeError as error:
        if str(error).startswith(('HTTP_401', 'HTTP_403')) or str(error) == 'NOT_TEST_MODE':
            emit('stopped', {'category': str(error), 'action': '需要用户检查密钥/权限或测试模式'})
            return 3
        payment_error = True
        emit('payment_blocked', {'category': str(error), 'action': '停止后续支付写入；费率与可用时间未实测'})
    emit('balance_after', balance_summary(stripe.request('/v1/balance')))
    payouts = stripe.request('/v1/payouts?limit=10')
    for payout in payouts['data']:
        test_mode(payout)
    emit('payouts', {'has_more': payouts['has_more'], 'data': [
        {key: p.get(key) for key in ('id', 'amount', 'currency', 'status', 'automatic', 'reconciliation_status')}
        for p in payouts['data']]})
    events = stripe.request('/v1/events?limit=5')
    for event in events['data']:
        test_mode(event)
    emit('events', [{key: e.get(key) for key in ('id', 'type', 'api_version')} for e in events['data']])
    event_id = find_event(stripe, bypass) if bypass else None
    emit('bypass_event', event_id)
    emit('cli', cli_probe(os.environ.get('STRIPE_CLI'), stripe.key, event_id,
                          os.environ.get('STRIPE_CLI_USE_LOGIN') == '1'))
    emit('deferred', {'1_auto_payout': '待 T07 观察', '4_pending_refund': '待 T07 经主应用发起退款观察',
                      '8_reconciliation_completed': '待 T07 观察'})
    emit('documentation_only', {
        '5_failed_payout': 'https://docs.stripe.com/connect/testing#payouts — Canada: 11000-000 / 000111111113, account_closed；未改账号',
        '6_retries': 'https://docs.stripe.com/webhooks#automatic-retries — sandbox 几小时内重试三次；未实测',
        '7_rate_limit': 'https://docs.stripe.com/rate-limits — sandbox 全局 25 请求/秒；未压测'})
    return 3 if payment_error else 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except ProbeError as error:
        emit('stopped', {'category': str(error)})
        sys.exit(3)
    except (KeyError, TypeError, ValueError, OSError):
        emit('stopped', {'category': 'LOCAL_STATE_OR_RESPONSE_ERROR'})
        sys.exit(3)
