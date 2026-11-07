"""Persistent, loopback-only sandbox lifecycle and a detached CLI supervisor."""
import argparse
import os
import re
import subprocess
import sys
import threading
import time
import uuid
from contextlib import contextmanager

from runtime import (APP, SIM, ROOT, STATE, OUT, EVENTS, PROBE_EVENT, Failure, cli, cli_command,
                     cli_json, compose, compose_args, compose_env, emit, get_json, instant,
                     load_run, read_json, require, run_process, safe, simulator_sql, utcnow,
                     read_secrets, update_secrets, ensure_database_passwords, purge_database_passwords,
                     validate_run, verify_account, wait_until, write_json)


@contextmanager
def lifecycle_lock():
    STATE.mkdir(exist_ok=True)
    lock = STATE / 'lifecycle.lock'
    try:
        lock.mkdir()
    except FileExistsError:
        raise Failure('LIFECYCLE_BUSY_OR_STALE_LOCK_REVIEW_REQUIRED') from None
    try:
        yield
    finally:
        lock.rmdir()


def listener_state():
    value = read_json(STATE / 'listen.json', {})
    value['alive'] = value.get('status') == 'running' and 0 <= time.time() - value.get('heartbeat', 0) < 10
    return value


def stop_listener():
    state = listener_state()
    if not state['alive']:
        return
    # Token-based shutdown goes to our supervisor, never kill a potentially reused PID.
    (STATE / 'listen.stop').write_text(state['token'], encoding='ascii')
    wait_until(listener_state, lambda s: not s['alive'], 15)


def supervise(token, run_id):
    command, env = cli_command(['listen', '--forward-to', SIM + '/relay/stripe', '--events', EVENTS])
    process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                               creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    ready = threading.Event()
    state = {'token': token, 'run_id': run_id, 'pid': process.pid, 'supervisor_pid': os.getpid()}

    def drain():
        with (STATE / 'listen.log').open('a', encoding='utf-8') as log:
            for raw in iter(process.stdout.readline, b''):
                line = raw.decode('utf-8', errors='replace')
                if 'Ready!' in line:
                    ready.set()
                log.write(safe(line))
                log.flush()

    reader = threading.Thread(target=drain, daemon=True)
    reader.start()
    try:
        while process.poll() is None:
            stop = STATE / 'listen.stop'
            if stop.exists() and stop.read_text(encoding='ascii') == token:
                break
            write_json(STATE / 'listen.json', {**state, 'status': 'running', 'ready': ready.is_set(),
                                             'heartbeat': time.time()})
            time.sleep(1)
    finally:
        if process.poll() is None:
            process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        reader.join(timeout=5)
        process.stdout.close()
        write_json(STATE / 'listen.json', {**state, 'status': 'stopped', 'ready': False,
                                         'heartbeat': time.time(), 'exit_code': process.returncode})


def start_listener(run):
    state = listener_state()
    if state['alive'] and state.get('ready') and state.get('run_id') == run['run_id']:
        emit({'listen': 'already running', 'pid': state['pid']})
        return
    stop_listener()
    token = uuid.uuid4().hex
    kwargs = {'creationflags': subprocess.DETACHED_PROCESS | subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {
        'start_new_session': True}
    subprocess.Popen([sys.executable, str(ROOT / 'scripts/sandbox/stack.py'), '_listen', token, run['run_id']],
                     cwd=ROOT, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                     stderr=subprocess.DEVNULL, **kwargs)
    wait_until(listener_state, lambda s: s.get('token') == token and s['alive'] and s.get('ready'), 45)
    emit({'listen': 'ready'})


def ensure_account():
    try:
        result = cli(['events', 'retrieve', PROBE_EVENT])
        value = cli_json(result.stdout)
    except Failure as error:
        if error.exit_code == 78:
            raise
        raise Failure('需要用户执行 stripe login；无法读取核对事件', 78) from None
    verify_account(value)
    emit({'login_account': 'verified', 'event': PROBE_EVENT, 'livemode': False})


def save_secret():
    result = cli(['listen', '--print-secret'])
    matches = re.findall(r'(?m)^\s*(whsec_[A-Za-z0-9]+)\s*$', result.stdout)
    if result.returncode != 0 or len(matches) != 1:
        raise Failure('需要用户执行 stripe login；无法取得本地签名密钥', 78)
    previous = read_secrets().get('STRIPE_WEBHOOK_SECRET')
    if previous != matches[0]:
        stop_listener()
        update_secrets({'STRIPE_WEBHOOK_SECRET': matches[0]})


def select_run(new_run, window_start):
    old = read_json(STATE / 'run.json')
    if old and not new_run:
        validate_run(old)
        require(window_start is None or window_start == old['window_start'], 'WINDOW_CHANGE_REQUIRES_NEW_RUN')
        return old
    if old:
        validate_run(old)
        write_json(STATE / 'runs' / (old['run_id'] + '.json'), old)
    now = utcnow()
    run = validate_run({'run_id': 'sbx_' + now.strftime('%Y%m%dT%H%M%S%fZ'),
                        'window_start': window_start or '2026-10-20T00:00:00Z'})
    write_json(STATE / 'run.json', run)
    return run


def probe_resend(run):
    def observed():
        return simulator_sql(run, "SELECT coalesce(max(id), 0) FROM sim_chaos.inbound_recording "
                             "WHERE event_id = '" + PROBE_EVENT + "';")
    before = observed()
    result = cli(['events', 'resend', PROBE_EVENT])
    value = cli_json(result.stdout)
    error = value.get('error')
    accepted = value.get('id') == PROBE_EVENT and value.get('livemode') is False and not error
    evidence = {'event_id': PROBE_EVENT, 'requested_at': instant(), 'cli_json_accepted': accepted,
                'relay_received': False, 'observation_seconds': 60, 'cli_exit_code': result.returncode}
    if error:
        code = error.get('code', 'UNCLASSIFIED')
        evidence['error_code'] = code if re.fullmatch(r'[a-zA-Z0-9_]{1,80}', str(code)) else 'UNCLASSIFIED'
    try:
        wait_until(observed, lambda count: count > before, 60, interval=2)
        evidence['relay_received'] = True
    except Failure as error:
        if str(error) != 'WAIT_TIMEOUT':
            raise
    write_json(OUT / ('resend-' + utcnow().strftime('%Y%m%dT%H%M%S%fZ') + '.json'), evidence)
    emit({'resend_probe': evidence})


def build_jars():
    for name, extra in (('app', []), ('simulator', ['-f', 'simulator/pom.xml'])):
        emit({'build': name})
        if os.name == 'nt':
            command = ['cmd.exe', '/d', '/c', '.\\mvnw.cmd']
        else:
            command = ['./mvnw']
        result = run_process([*command, '-B', *extra, '-DskipTests', 'package'], timeout=600)
        (STATE / (name + '-build.log')).write_text(safe(result.stdout + result.stderr), encoding='utf-8')
        require(result.returncode == 0, 'JAR_BUILD_FAILED_SEE_SANDBOX_LOG')


def up(args):
    with lifecycle_lock():
        ensure_account()
        run = select_run(args.new_run, args.window_start)
        ensure_database_passwords()
        save_secret()
        build_jars()
        emit({'compose': 'building and starting', 'run': run})
        result = run_process(compose_args('up', '-d', '--build', '--wait', '--wait-timeout', '180'),
                             compose_env(run), timeout=600)
        (STATE / 'compose.log').write_text(safe(result.stdout + result.stderr), encoding='utf-8')
        require(result.returncode == 0, 'COMPOSE_UP_FAILED_SEE_SANDBOX_LOG')
        start_listener(run)
        emit({'resend_probe': 'observing relay for up to 60 seconds'})
        probe_resend(run)
        emit({'up': 'PASS', 'run': run, 'app': APP, 'simulator': SIM})


def down(args):
    with lifecycle_lock():
        run = load_run()
        if args.purge:
            print('删除沙箱卷需输入当前 run_id: ' + run['run_id'], flush=True)
            require(input().strip() == run['run_id'], 'PURGE_NOT_CONFIRMED')
        stop_listener()
        compose(run, 'down', *(['--volumes'] if args.purge else []), timeout=180)
        if args.purge:
            purge_database_passwords()
            write_json(STATE / 'runs' / (run['run_id'] + '-purged.json'), run)
            (STATE / 'run.json').unlink()
        emit({'down': 'PASS', 'volumes': 'deleted' if args.purge else 'preserved'})


def status():
    run = load_run()
    state = listener_state()
    services = compose(run, 'ps', '--format', 'json')
    health = {}
    for name, base, path in (('app', APP, '/api/overview'), ('simulator', SIM, '/sim/clock')):
        try:
            get_json(base, path)
            health[name] = 'UP'
        except Failure:
            health[name] = 'DOWN'
    emit({'run': run, 'listen': state, 'containers': services, 'health': health})
    return 0 if state['alive'] and state.get('ready') and all(v == 'UP' for v in health.values()) else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    start = commands.add_parser('up')
    start.add_argument('--new-run', action='store_true')
    start.add_argument('--window-start')
    stop = commands.add_parser('down')
    stop.add_argument('--purge', action='store_true')
    commands.add_parser('status')
    background = commands.add_parser('_listen')
    background.add_argument('token')
    background.add_argument('run_id')
    args = parser.parse_args()
    try:
        if args.command == 'up':
            up(args)
        elif args.command == 'down':
            down(args)
        elif args.command == 'status':
            return status()
        else:
            supervise(args.token, args.run_id)
        return 0
    except Failure as error:
        emit({'result': 'FAIL', 'reason': str(error)})
        return error.exit_code
    except (OSError, ValueError, KeyError, TypeError, EOFError):
        emit({'result': 'FAIL', 'reason': 'INVALID_STATE_OR_LOCAL_IO'})
        return 1


if __name__ == '__main__':
    sys.exit(main())
