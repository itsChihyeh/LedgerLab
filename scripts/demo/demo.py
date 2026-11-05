"""Local break-it demo stack lifecycle (docs/design/demo-and-delivery.md sections 1 and 5).

Commands: up, down, status, reset, check-reset. Every Docker call names the project `ledgerlab-demo` and the
compose file explicitly, so the persistent `ledgerlab-sandbox` stack is never touched. The database passwords are
generated on first use into .demo/secrets.env (git-ignored); .env is never read. Python 3.9+, standard library only.
`--behind-proxy` (or LEDGERLAB_DEMO_BEHIND_PROXY=1, which the cron reset needs too) makes the app trust the Compose
gateway 10.89.77.1 as a reverse proxy, so each visitor is rate limited by his own X-Forwarded-For address (AC-9.10).
"""
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

GATEWAY_CIDR = '10.89.77.1/32'
ROOT = Path(__file__).resolve().parents[2]
STATE = ROOT / '.demo'
SECRETS = STATE / 'secrets.env'
ARCHIVE = STATE / 'archive'
OUT = ROOT / 'target' / 'demo'
PROJECT = 'ledgerlab-demo'
COMPOSE_FILE = 'compose.demo.yaml'
APP = 'http://127.0.0.1:28080'
SIM = 'http://127.0.0.1:28081'
PASSWORDS = ('POSTGRES_PASSWORD', 'LEDGERLAB_MIGRATOR_PASSWORD', 'LEDGERLAB_APP_PASSWORD',
             'LEDGERLAB_READONLY_PASSWORD', 'LEDGERLAB_SIMULATOR_PASSWORD')
LAST_RESET_FILE = 'last-reset.txt'
CLOCK_HEAD_START_DAYS = 14
ARCHIVE_KEEP_DAYS = 7
# Same public synthetic values the disposable smoke stacks use; no real Stripe credential exists in this stack.
FAKE_ENV_FILE = ROOT / 'compose.smoke.env'
FAKE_ENV_NAMES = {'SMOKE_STRIPE_API_KEY': 'DEMO_FAKE_STRIPE_API_KEY',
                  'SMOKE_STRIPE_WEBHOOK_SECRET': 'DEMO_FAKE_STRIPE_WEBHOOK_SECRET'}
ACTIONS_FOR_CHECK = ('pay', 'replay-webhook', 'double-refund')


class Failure(Exception):
    pass


# ---------------------------------------------------------------- pure helpers (see selftest.py)

def utcnow():
    return datetime.now(timezone.utc)


def instant(moment):
    return moment.astimezone(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')


def new_secrets(token=lambda: secrets.token_urlsafe(24)):
    """One fresh password per database role; never reused across roles."""
    return {name: token() for name in PASSWORDS}


def format_env(values):
    return ''.join('%s=%s\n' % (name, value) for name, value in values.items())


def parse_env(text):
    values = {}
    for line in text.splitlines():
        line = line.strip().rstrip('\r')
        if not line or line.startswith('#') or '=' not in line:
            continue
        name, value = line.split('=', 1)
        values[name.strip()] = value.strip().strip('"\'')
    return values


def sim_clock_start(now, days=CLOCK_HEAD_START_DAYS):
    return instant(now - timedelta(days=days))


def archive_filename(now, existing):
    """`<UTC date>.json`; a second reset on the same day keeps the first file and gets a time suffix."""
    plain = now.astimezone(timezone.utc).strftime('%Y-%m-%d') + '.json'
    if plain not in existing:
        return plain
    return now.astimezone(timezone.utc).strftime('%Y-%m-%dT%H%M%SZ') + '.json'


def verdict(audit):
    """The same shape the demo module publishes after each action, from /api/audit/invariants."""
    checks = [{'id': check.get('id'), 'ok': bool(check.get('ok'))} for check in audit.get('checks', [])]
    failed = [check['id'] for check in checks if not check['ok']]
    return {'checkedAt': audit.get('checkedAt'), 'ok': bool(checks) and not failed,
            'checks': checks, 'failed': failed}


def archive_record(summary, audit, now):
    record = dict(summary)
    record['invariants'] = verdict(audit)
    record['archivedAt'] = instant(now)
    return record


def prune(names, keep=ARCHIVE_KEEP_DAYS):
    """Archive files to delete from the host directory: everything but the newest `keep` (names sort by date)."""
    dated = sorted(n for n in names if re.match(r'^\d{4}-\d{2}-\d{2}.*\.json$', n))
    return dated[:-keep] if len(dated) > keep else []


def all_zero(counts):
    return all(value == 0 for value in counts.values())


# ---------------------------------------------------------------- process and HTTP plumbing

def say(text):
    print(text, flush=True)


def python_env():
    env = {k: v for k, v in os.environ.items()
           if not k.startswith('COMPOSE_') and k not in PASSWORDS and not k.startswith('STRIPE_')}
    env['COMPOSE_DISABLE_ENV_FILE'] = '1'
    return env


def load_or_create_secrets():
    STATE.mkdir(exist_ok=True)
    if SECRETS.exists():
        values = parse_env(SECRETS.read_text(encoding='utf-8'))
        if all(values.get(name) for name in PASSWORDS):
            return values
    values = new_secrets()
    SECRETS.write_text(format_env(values), encoding='utf-8')
    try:
        os.chmod(SECRETS, 0o600)
    except OSError:
        pass
    return values


def trusted_proxies(environ):
    """The Compose gateway is the reverse proxy's address when the stack sits behind one (AC-9.10)."""
    return GATEWAY_CIDR if environ.get('LEDGERLAB_DEMO_BEHIND_PROXY', '').strip() in ('1', 'true') else ''


def compose_env(passwords, now=None):
    fake = parse_env(FAKE_ENV_FILE.read_text(encoding='utf-8'))
    env = python_env()
    env.update(passwords)
    for source, target in FAKE_ENV_NAMES.items():
        env[target] = fake[source]
    env['LEDGERLAB_DEMO_TRUSTED_PROXIES'] = trusted_proxies(os.environ)
    env['DEMO_SIM_CLOCK_START'] = sim_clock_start(now or utcnow())
    ARCHIVE.mkdir(parents=True, exist_ok=True)
    env['DEMO_ARCHIVE_DIR'] = ARCHIVE.as_posix()
    return env


def compose(*args, passwords=None, capture=False, check=True):
    passwords = passwords or load_or_create_secrets()
    command = ['docker', 'compose', '-f', COMPOSE_FILE, '-p', PROJECT, *args]
    result = subprocess.run(command, cwd=ROOT, env=compose_env(passwords), text=True,
                            capture_output=capture, check=False)
    if check and result.returncode != 0:
        raise Failure('docker compose %s failed (%d)%s' % (
            args[0], result.returncode, ': ' + result.stderr.strip() if capture and result.stderr else ''))
    return result


def run(command, **kwargs):
    result = subprocess.run(command, cwd=ROOT, env=python_env(), text=True, check=False, **kwargs)
    if result.returncode != 0:
        raise Failure('%s failed (%d)' % (command[0], result.returncode))
    return result


def http(method, url, timeout=120):
    request = Request(url, method=method, data=b'' if method == 'POST' else None)
    try:
        with urlopen(request, timeout=timeout) as response:
            body = response.read()
            return response.status, json.loads(body) if body else None
    except HTTPError as error:
        body = error.read()
        try:
            return error.code, json.loads(body) if body else None
        except ValueError:
            return error.code, None


def app_json(method, path, timeout=120):
    status, body = http(method, APP + path, timeout)
    if status != 200:
        raise Failure('%s %s answered %s' % (method, path, status))
    return body


def wait_for(probe, seconds, what):
    deadline = time.time() + seconds
    last = None
    while time.time() < deadline:
        try:
            if probe():
                return
        except (URLError, OSError, Failure) as error:
            last = error
        time.sleep(2)
    raise Failure('Timed out waiting for %s%s' % (what, ' (%s)' % last if last else ''))


def stack_running():
    result = compose('ps', '-q', capture=True, check=False)
    return result.returncode == 0 and bool(result.stdout.strip())


def app_reachable():
    try:
        status, _ = http('GET', APP + '/api/overview', 5)
        return status == 200
    except (URLError, OSError):
        return False


def sandbox_containers():
    result = subprocess.run(['docker', 'ps', '--format', '{{.Names}}'], capture_output=True, text=True, check=False)
    return sorted(n for n in result.stdout.split() if n.startswith('ledgerlab-sandbox'))


def demo_leftovers():
    """Containers, volumes and networks of the demo project that still exist."""
    found = []
    for kind, command in (('container', ['docker', 'ps', '-a', '--filter', 'label=com.docker.compose.project=' + PROJECT,
                                          '--format', '{{.Names}}']),
                          ('volume', ['docker', 'volume', 'ls', '--filter', 'label=com.docker.compose.project=' + PROJECT,
                                      '--format', '{{.Name}}']),
                          ('network', ['docker', 'network', 'ls', '--filter', 'label=com.docker.compose.project=' + PROJECT,
                                       '--format', '{{.Name}}'])):
        result = subprocess.run(command, capture_output=True, text=True, check=False)
        found += ['%s:%s' % (kind, n) for n in result.stdout.split()]
    return found


# ---------------------------------------------------------------- commands

def build_artifacts():
    mvnw = [str(ROOT / 'mvnw.cmd')] if os.name == 'nt' else ['./mvnw']
    say('Building the app and simulator jars...')
    run(mvnw + ['-B', '-q', '-DskipTests', 'package'])
    run(mvnw + ['-B', '-q', '-f', 'simulator/pom.xml', '-DskipTests', 'package'])


def check_invariants_ok():
    audit = app_json('GET', '/api/audit/invariants')
    result = verdict(audit)
    if not result['ok']:
        raise Failure('Invariants failed: %s' % (', '.join(result['failed']) or 'no checks returned'))
    return result


def write_last_reset(now):
    ARCHIVE.mkdir(parents=True, exist_ok=True)
    (ARCHIVE / LAST_RESET_FILE).write_text(instant(now) + '\n', encoding='utf-8')


def cmd_up(build=True):
    if shutil.which('docker') is None:
        raise Failure('docker is not installed or not on PATH')
    run(['docker', 'info'], capture_output=True)
    passwords = load_or_create_secrets()
    if build:
        build_artifacts()
    say('Starting project %s (app %s, simulator %s, postgres 127.0.0.1:25432)...' % (PROJECT, APP, SIM))
    compose('up', '-d', '--build', '--wait', '--wait-timeout', '300', passwords=passwords)
    wait_for(app_reachable, 60, 'the app')
    counters = app_json('GET', '/api/demo/summary')['counters']
    if counters.get('payments', 0) == 0:
        say('Running the seed scenario (3 settled payments, 1 refund, 1 BANKED batch)...')
        app_json('POST', '/api/demo/seed', 300)
        write_last_reset(utcnow())
    else:
        say('Already seeded; leaving the data as it is.')
    result = check_invariants_ok()
    say('All %d invariants hold. Demo page: %s/' % (len(result['checks']), APP))
    return result


def cmd_down():
    say('Removing project %s with its volumes...' % PROJECT)
    compose('down', '-v', '--remove-orphans')
    say('Down. Secrets and the archive stay in .demo/.')


def cmd_status():
    compose('ps')
    if not app_reachable():
        say('The app is not answering on %s.' % APP)
        return 1
    summary = app_json('GET', '/api/demo/summary')
    archive = app_json('GET', '/api/demo/archive')
    say('Counters: %s' % json.dumps(summary['counters']))
    say('Actions today: %s' % json.dumps(summary['actionsToday']))
    say('Invariants ok: %s' % summary['invariants'].get('ok'))
    say('Last reset: %s; archived summaries: %d' % (archive.get('lastReset'), len(archive.get('archive', []))))
    return 0


def extra_archive_dir():
    value = os.environ.get('DEMO_ARCHIVE_EXTRA_DIR', '').strip()
    return Path(value) if value else None


def archive_current(now):
    """Saves today's summary before the reset. Returns the file name, or None when there is no stack to read."""
    if not app_reachable():
        say('The demo app is not running; nothing to archive.')
        return None
    summary = app_json('GET', '/api/demo/summary')
    audit = app_json('GET', '/api/audit/invariants')
    record = archive_record(summary, audit, now)
    ARCHIVE.mkdir(parents=True, exist_ok=True)
    name = archive_filename(now, {p.name for p in ARCHIVE.iterdir()})
    text = json.dumps(record, indent=2, sort_keys=True) + '\n'
    (ARCHIVE / name).write_text(text, encoding='utf-8')
    for stale in prune([p.name for p in ARCHIVE.iterdir()]):
        (ARCHIVE / stale).unlink()
    extra = extra_archive_dir()
    if extra is not None:
        extra.mkdir(parents=True, exist_ok=True)
        (extra / name).write_text(text, encoding='utf-8')
        say('Also archived to %s' % (extra / name))
    say('Archived the summary to %s' % (ARCHIVE / name))
    return name


def cmd_reset(build=True):
    archived = archive_current(utcnow())
    cmd_down()
    result = cmd_up(build=build)
    return archived, result


def sandbox_guard(before):
    after = sandbox_containers()
    if after != before:
        raise Failure('ledgerlab-sandbox containers changed: %s -> %s' % (before, after))


def cmd_check_reset():
    started = utcnow()
    OUT.mkdir(parents=True, exist_ok=True)
    evidence = {'startedAt': instant(started), 'steps': []}
    sandbox_before = sandbox_containers()
    evidence['sandboxContainersBefore'] = sandbox_before
    outcome = 'FAILED'
    try:
        def step(label, **data):
            evidence['steps'].append({'step': label, **data})
            say('check-reset: %s' % label)

        step('down any previous demo stack')
        cmd_down()
        step('up')
        cmd_up()
        seeded = app_json('GET', '/api/demo/summary')['counters']
        before = app_json('GET', '/api/demo/archive')
        previous_reset = before.get('lastReset')
        if not previous_reset:
            raise Failure('lastReset was not written by up')
        for action in ACTIONS_FOR_CHECK:
            step('action', name=action)
            status, body = http('POST', '%s/api/demo/actions/%s' % (APP, action), 300)
            if status != 200:
                raise Failure('action %s answered %s' % (action, status))
            if not body['invariants']['ok']:
                raise Failure('invariants failed after %s: %s' % (action, body['invariants']['failed']))
        used = app_json('GET', '/api/demo/summary')
        if used['actionsToday']['pay'] < 1 or used['actionsToday']['replay-webhook'] < 1:
            raise Failure('action counts were not recorded: %s' % used['actionsToday'])
        evidence['beforeReset'] = {'counters': used['counters'], 'actionsToday': used['actionsToday']}

        time.sleep(1.2)  # lastReset has one-second resolution
        step('reset')
        archived, _ = cmd_reset(build=False)
        if not archived or not (ARCHIVE / archived).exists():
            raise Failure('no archive file was written')
        stored = json.loads((ARCHIVE / archived).read_text(encoding='utf-8'))
        if stored['actionsToday']['pay'] != used['actionsToday']['pay'] or not stored['invariants']['ok']:
            raise Failure('the archive does not match the summary taken before the reset')
        evidence['archiveFile'] = archived

        after = app_json('GET', '/api/demo/summary')
        if not all_zero(after['actionsToday']):
            raise Failure('action counts were not reset: %s' % after['actionsToday'])
        if after['counters'] != seeded or used['counters'] == seeded:
            raise Failure('the database was not reset to the seed: %s vs %s' % (after['counters'], seeded))
        if not check_invariants_ok()['ok']:
            raise Failure('invariants do not hold after the reset')
        listing = app_json('GET', '/api/demo/archive')
        if not listing.get('lastReset') or listing['lastReset'] <= previous_reset:
            raise Failure('lastReset did not advance: %s -> %s' % (previous_reset, listing.get('lastReset')))
        if archived not in [entry['file'] for entry in listing['archive']]:
            raise Failure('GET /api/demo/archive does not list %s' % archived)
        evidence['afterReset'] = {'counters': after['counters'], 'actionsToday': after['actionsToday'],
                                  'lastReset': listing['lastReset'], 'archivedSummaries': len(listing['archive'])}
        sandbox_guard(sandbox_before)
        step('down')
        cmd_down()
        leftovers = demo_leftovers()
        if leftovers:
            raise Failure('left behind after down: %s' % leftovers)
        sandbox_guard(sandbox_before)
        outcome = 'PASSED'
    except Failure as failure:
        evidence['failure'] = str(failure)
        say('check-reset FAILED: %s' % failure)
        try:
            log = compose('logs', '--no-color', '--tail', '200', capture=True, check=False).stdout
            (OUT / 'compose.log').write_text(log or '', encoding='utf-8')
            cmd_down()
        except Failure:
            pass
    finally:
        evidence['outcome'] = outcome
        evidence['finishedAt'] = instant(utcnow())
        evidence['sandboxContainersAfter'] = sandbox_containers()
        path = OUT / ('check-reset-%s.json' % started.strftime('%Y%m%dT%H%M%SZ'))
        path.write_text(json.dumps(evidence, indent=2, sort_keys=True) + '\n', encoding='utf-8')
        say('check-reset %s. Evidence: %s' % (outcome, path.relative_to(ROOT).as_posix()))
    return 0 if outcome == 'PASSED' else 1


def main(argv):
    if len(argv) < 2 or argv[1] not in ('up', 'down', 'status', 'reset', 'check-reset'):
        print('usage: demo.py up|down|status|reset|check-reset [--behind-proxy]', file=sys.stderr)
        return 2
    sys.stdout.reconfigure(encoding='utf-8')
    command = argv[1]
    if '--behind-proxy' in argv[2:]:
        os.environ['LEDGERLAB_DEMO_BEHIND_PROXY'] = '1'
    try:
        if command == 'up':
            cmd_up()
        elif command == 'down':
            cmd_down()
        elif command == 'status':
            return cmd_status()
        elif command == 'reset':
            cmd_reset()
        else:
            return cmd_check_reset()
    except Failure as failure:
        print('demo %s failed: %s' % (command, failure), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
