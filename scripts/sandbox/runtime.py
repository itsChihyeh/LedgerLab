"""Shared sandbox I/O. REST key comes from env.sh; local credentials stay in .sandbox."""
import hashlib
import json
import os
import re
import secrets
import subprocess
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, build_opener

from probe import NoRedirect, redact, cli_args

ROOT = Path(__file__).resolve().parents[2]
STATE = ROOT / '.sandbox'
OUT = ROOT / 'target/sandbox'
APP = 'http://127.0.0.1:18080'
SIM = 'http://127.0.0.1:18081'
PROBE_EVENT = 'evt_3UXQCaF5hIinUF490brSX7aS'
EVENTS = ('charge.succeeded,refund.created,refund.updated,refund.failed,payout.created,'
          'payout.reconciliation_completed,payout.paid,payout.failed,payout.canceled,charge.dispute.created')
PASSWORDS = ('POSTGRES_PASSWORD', 'LEDGERLAB_MIGRATOR_PASSWORD', 'LEDGERLAB_APP_PASSWORD',
             'LEDGERLAB_READONLY_PASSWORD', 'LEDGERLAB_SIMULATOR_PASSWORD')
SECRET_VALUES = set()


class Failure(Exception):
    def __init__(self, code, exit_code=1):
        super().__init__(code)
        self.exit_code = exit_code


def require(condition, code):
    if not condition:
        raise Failure(code)


def utcnow():
    return datetime.now(timezone.utc)


def instant(now=None):
    return (now or utcnow()).isoformat().replace('+00:00', 'Z')


def safe(text):
    for value in SECRET_VALUES:
        text = text.replace(value, '[REDACTED]')
    for name in (*PASSWORDS, 'STRIPE_API_KEY', 'STRIPE_WEBHOOK_SECRET'):
        value = os.environ.get(name)
        if value:
            text = text.replace(value, '[REDACTED]')
    return redact(text)


def emit(value):
    print(safe(json.dumps(value, ensure_ascii=False)), flush=True)


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)


def read_json(path, default=None):
    if not path.exists():
        return default
    try:
        return json.loads(path.read_text(encoding='utf-8'))
    except (ValueError, OSError):
        raise Failure('INVALID_LOCAL_STATE') from None


def validate_run(run):
    require(isinstance(run, dict) and re.fullmatch(r'sbx_[A-Za-z0-9_-]{1,100}', run.get('run_id', '')),
            'INVALID_RUN_ID')
    try:
        timestamp = datetime.fromisoformat(run['window_start'].replace('Z', '+00:00'))
        require(timestamp.tzinfo is not None, 'WINDOW_REQUIRES_TIMEZONE')
    except (ValueError, KeyError, TypeError):
        raise Failure('INVALID_WINDOW_START') from None
    return run


def load_run():
    run = read_json(STATE / 'run.json')
    require(run is not None, 'RUN_MISSING_RUN_UP_FIRST')
    return validate_run(run)


def read_secrets():
    path = STATE / 'secrets.env'
    values = {}
    if not path.exists():
        return values
    for line in path.read_text(encoding='utf-8').splitlines():
        if not line.strip() or line.startswith('#'):
            continue
        key, separator, value = line.partition('=')
        require(separator and key in (*PASSWORDS, 'STRIPE_WEBHOOK_SECRET') and key not in values
                and re.fullmatch(r'[A-Za-z0-9_-]+', value), 'INVALID_SAVED_SECRET')
        values[key] = value
        SECRET_VALUES.add(value)
    return values


def update_secrets(updates, remove=()):
    values = read_secrets()
    values.update(updates)
    for key in remove:
        values.pop(key, None)
    STATE.mkdir(parents=True, exist_ok=True)
    # Secure the temporary file before writing, then atomically replace the old file.
    fd, name = tempfile.mkstemp(prefix='secrets-', suffix='.tmp', dir=STATE)
    temporary = Path(name)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as stream:
            os.chmod(temporary, 0o600)
            if os.name == 'nt':
                owner = run_process(['whoami', '/user', '/fo', 'csv', '/nh'])
                sid = re.search(r'S-1-5-[0-9-]+', owner.stdout)
                require(sid is not None, 'SECRET_ACL_OWNER_UNKNOWN')
                acl = run_process(['icacls', str(temporary), '/inheritance:r', '/grant:r',
                                   '*' + sid.group() + ':(F)'])
                require(acl.returncode == 0, 'SECRET_ACL_FAILED')
            stream.write(''.join(key + '=' + value + '\n' for key, value in values.items()))
            stream.flush()
            os.fsync(stream.fileno())
        temporary.replace(STATE / 'secrets.env')
        SECRET_VALUES.update(values.values())
    finally:
        temporary.unlink(missing_ok=True)


def ensure_database_passwords():
    values = read_secrets()
    missing = {key: secrets.token_hex(32) for key in PASSWORDS if key not in values}
    if missing:
        update_secrets(missing)


def purge_database_passwords():
    update_secrets({}, remove=PASSWORDS)


def load_secret():
    value = read_secrets().get('STRIPE_WEBHOOK_SECRET', '')
    require(re.fullmatch(r'whsec_[A-Za-z0-9]+', value), 'INVALID_SIGNING_SECRET_RUN_UP_FIRST')
    return value


def compose_env(run):
    env = dict(os.environ)
    saved = read_secrets()
    require(all(saved.get(key) for key in PASSWORDS), 'DATABASE_PASSWORD_MISSING_RUN_UP_FIRST')
    env.update({key: saved[key] for key in PASSWORDS})
    require(re.fullmatch(r'(rk|sk)_test_[A-Za-z0-9_]+', env.get('STRIPE_API_KEY', '')), 'INVALID_TEST_KEY')
    env.update(STRIPE_WEBHOOK_SECRET=load_secret(), SIM_SANDBOX_RUN_ID=run['run_id'],
               SIM_SANDBOX_WINDOW_START=run['window_start'], SANDBOX_CLOCK_START=instant(),
               COMPOSE_DISABLE_ENV_FILE='1')
    for name in ('COMPOSE_FILE', 'COMPOSE_PROFILES', 'COMPOSE_ENV_FILES'):
        env.pop(name, None)
    return env


def compose_args(*args):
    # A deliberately empty file prevents implicit Compose .env interpolation.
    empty = STATE / 'compose.empty.env'
    empty.parent.mkdir(parents=True, exist_ok=True)
    empty.touch(exist_ok=True)
    return ['docker', 'compose', '--env-file', str(empty), '-f', 'compose.yaml',
            '-f', 'compose.sandbox.yaml', '-p', 'ledgerlab-sandbox', '--profile', 'sandbox', *args]


def run_process(command, env=None, timeout=60, input_text=None):
    try:
        return subprocess.run(command, cwd=ROOT, env=env, input=input_text, capture_output=True,
                              text=True, encoding='utf-8', errors='replace', timeout=timeout)
    except subprocess.TimeoutExpired:
        raise Failure('PROCESS_TIMEOUT') from None
    except OSError:
        raise Failure('PROCESS_UNAVAILABLE') from None


def compose(run, *args, timeout=60):
    result = run_process(compose_args(*args), compose_env(run), timeout)
    require(result.returncode == 0, 'COMPOSE_FAILED')
    return result.stdout


def cli_command(args):
    cli = os.environ.get('STRIPE_CLI', '')
    if not cli:
        raise Failure('需要用户执行 stripe login（并确认 Stripe CLI 已安装）', 78)
    return cli_args(cli, os.environ['STRIPE_API_KEY'], args,
                    login=os.environ.get('STRIPE_CLI_USE_LOGIN', '1') == '1')


def cli(args):
    command, env = cli_command(args)
    return run_process(command, env, timeout=45)


def cli_json(output):
    # Stripe CLI can return exit code 0 for an API error. Inspect the JSON body.
    try:
        value = json.loads(output)
        require(isinstance(value, dict), 'INVALID_CLI_JSON')
        return value
    except ValueError:
        raise Failure('INVALID_CLI_JSON') from None


def verify_account(value):
    if value.get('error', {}).get('code') == 'resource_missing':
        raise Failure('请用户重新 stripe login，改选另一个沙盒环境（测试模式）', 78)
    if value.get('id') != PROBE_EVENT or value.get('livemode') is not False:
        raise Failure('需要用户执行 stripe login；未能核对测试沙盒事件', 78)


def request(base, path, method='GET', body=None, headers=None, timeout=15):
    headers = dict(headers or {})
    if body is not None and not isinstance(body, bytes):
        body = json.dumps(body).encode()
        headers['Content-Type'] = 'application/json'
    try:
        with build_opener(NoRedirect()).open(Request(base + path, body, headers, method=method),
                                             timeout=timeout) as response:
            return response.status, response.read()
    except HTTPError as error:
        return error.code, error.read()
    except (URLError, OSError, TimeoutError):
        raise Failure('HTTP_CONNECTION_FAILED') from None


def get_json(base, path, method='GET', body=None, timeout=15, missing_ok=False):
    status, raw = request(base, path, method, body, timeout=timeout)
    if missing_ok and status == 404:
        return None
    require(200 <= status < 300, f'HTTP_{status}')
    try:
        return json.loads(raw)
    except ValueError:
        raise Failure('INVALID_HTTP_JSON') from None


def simulator_sql(run, sql, timeout=60):
    # Observer-only access as simulator, as in smoke/p3.py. Never app or sim_truth.
    env = compose_env(run)
    env['PGPASSWORD'] = env['LEDGERLAB_SIMULATOR_PASSWORD']
    result = run_process(compose_args('exec', '-T', '-e', 'PGPASSWORD', 'postgres', 'psql',
                         '-h', '127.0.0.1', '-U', 'simulator', '-d', 'ledgerlab',
                         '-XAt', '-v', 'ON_ERROR_STOP=1'), env, timeout=timeout, input_text=sql)
    require(result.returncode == 0, 'RECORDING_QUERY_FAILED')
    try:
        return json.loads(result.stdout)
    except ValueError:
        raise Failure('INVALID_RECORDING_RESULT') from None


def recordings(run, charges, timeout=60):
    require(charges and all(re.fullmatch(r'ch_[A-Za-z0-9]+', ref) for ref in charges), 'INVALID_CHARGE_IDS')
    ids = ','.join("'" + ref + "'" for ref in charges)
    return simulator_sql(run, "SELECT coalesce(json_agg(json_build_object('event_id', event_id, "
                         "'sha256', sha256, 'body_hex', encode(body, 'hex')) ORDER BY id), '[]') "
                         "FROM sim_chaos.inbound_recording WHERE event_type = 'charge.succeeded' "
                         "AND convert_from(body, 'UTF8')::jsonb #>> '{data,object,id}' IN (" + ids + ');', timeout)


def decode_recording(row):
    body = bytes.fromhex(row['body_hex'])
    require(hashlib.sha256(body).hexdigest() == row['sha256'], 'RECORDING_HASH_MISMATCH')
    event = json.loads(body)
    require(event.get('id') == row['event_id'] and event.get('type') == 'charge.succeeded'
            and event.get('livemode') is False, 'INVALID_RECORDED_EVENT')
    return body, event


def wait_until(action, predicate, seconds, interval=1, monotonic=time.monotonic, sleep=time.sleep):
    deadline = monotonic() + seconds
    while True:
        value = action()
        if predicate(value):
            return value
        remaining = deadline - monotonic()
        require(remaining > 0, 'WAIT_TIMEOUT')
        sleep(min(interval, remaining))
