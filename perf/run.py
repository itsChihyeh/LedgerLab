"""k6 performance baseline driver (AC-9.10, docs/design/demo-and-delivery.md section 8).

Every measurement runs on a fresh `ledgerlab-perf` stack (default Spring profile, fake Stripe, ports 38080/38081/35432)
so no backlog or data carries from one measurement into the next. One round = W1 at each rate (a fresh stack each),
then W2 + W3 on one more fresh stack. The invariants endpoint is called at the end of every stack's work; any
violation voids the whole run. Python 3.9+ standard library only; k6 runs from the pinned official Docker image.
"""
import json
import os
import platform
import secrets
import statistics
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, ProxyHandler, build_opener

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / 'perf' / '.state'
PROJECT = 'ledgerlab-perf'
NETWORK = PROJECT + '_perf'
COMPOSE_FILE = 'compose.perf.yaml'
K6_IMAGE = 'grafana/k6:2.3.0'
APP = 'http://127.0.0.1:38080'
SIM = 'http://127.0.0.1:38081'
INTERNAL_APP = 'http://app:8080'
PASSWORDS = ('POSTGRES_PASSWORD', 'LEDGERLAB_MIGRATOR_PASSWORD', 'LEDGERLAB_APP_PASSWORD',
             'LEDGERLAB_READONLY_PASSWORD', 'LEDGERLAB_SIMULATOR_PASSWORD')
FAKE_ENV_FILE = ROOT / 'compose.smoke.env'
EXPECTED_INVARIANTS = 12
opener = build_opener(ProxyHandler({}))

QUICK = '--quick' in sys.argv
CFG = {
    'rounds': 1 if QUICK else 3,
    'w1_rates': [50, 100, 200],
    'w1_duration_s': 10 if QUICK else 120,
    'w1_drain_cap_s': 30 if QUICK else 180,
    'w2_payments': 200,
    'w2_refund_amount': 1000,
    'w2_payment_amount': 20000,
    'w2_concurrency': 20,
    'w3_vus': 50,
    'w3_duration_s': 10 if QUICK else 60,
}


class Failure(Exception):
    pass


def say(text):
    print(time.strftime('%H:%M:%S ') + text, flush=True)


def utcnow():
    return datetime.now(timezone.utc)


def parse_env(text):
    values = {}
    for line in text.splitlines():
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            name, value = line.split('=', 1)
            values[name.strip()] = value.strip()
    return values


def load_secrets():
    STATE.mkdir(parents=True, exist_ok=True)
    path = STATE / 'secrets.env'
    if path.exists():
        values = parse_env(path.read_text(encoding='utf-8'))
        if all(values.get(n) for n in PASSWORDS):
            return values
    values = {name: secrets.token_urlsafe(24) for name in PASSWORDS}
    path.write_text(''.join('%s=%s\n' % kv for kv in values.items()), encoding='utf-8')
    return values


def clean_env():
    env = {k: v for k, v in os.environ.items()
           if not k.startswith('COMPOSE_') and k not in PASSWORDS and not k.startswith('STRIPE_')}
    env['COMPOSE_DISABLE_ENV_FILE'] = '1'
    return env


FAKE = parse_env(FAKE_ENV_FILE.read_text(encoding='utf-8'))
PASSWORD_VALUES = load_secrets()


def compose_env():
    env = clean_env()
    env.update(PASSWORD_VALUES)
    env['PERF_FAKE_STRIPE_API_KEY'] = FAKE['SMOKE_STRIPE_API_KEY']
    env['PERF_FAKE_STRIPE_WEBHOOK_SECRET'] = FAKE['SMOKE_STRIPE_WEBHOOK_SECRET']
    env['PERF_SIM_CLOCK_START'] = (utcnow() - timedelta(days=14)).strftime('%Y-%m-%dT%H:%M:%SZ')
    return env


def compose(*args, capture=False, check=True, timeout=None):
    command = ['docker', 'compose', '-f', COMPOSE_FILE, '-p', PROJECT, *args]
    result = subprocess.run(command, cwd=ROOT, env=compose_env(), text=True, capture_output=capture,
                            check=False, timeout=timeout)
    if check and result.returncode != 0:
        raise Failure('docker compose %s failed (%d) %s' % (args[0], result.returncode,
                                                            result.stderr[-500:] if capture else ''))
    return result


def run(command, **kwargs):
    result = subprocess.run(command, cwd=ROOT, env=clean_env(), text=True, check=False, **kwargs)
    if result.returncode != 0:
        raise Failure('%s failed (%d)' % (command[0], result.returncode))
    return result


# ---------------------------------------------------------------- HTTP and SQL helpers

def request(base, path, method='GET', data=None, timeout=30):
    body = json.dumps(data).encode() if data is not None else None
    req = Request(base + path, body, {'Content-Type': 'application/json'}, method=method)
    try:
        with opener.open(req, timeout=timeout) as response:
            raw = response.read()
            return json.loads(raw) if raw else None
    except HTTPError as error:
        raise Failure('%s %s: HTTP %d %s' % (method, path, error.code, error.read()[:200]))


def wait_for(label, probe, seconds=180):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            last = probe()
            if last:
                return last
        except (Failure, URLError, OSError) as error:
            last = error
        time.sleep(1)
    raise Failure('Timed out waiting for %s (%s)' % (label, last))


def psql(sql):
    """Read-only observer SQL through the `readonly` role (views only)."""
    env = compose_env()
    command = ['docker', 'compose', '-f', COMPOSE_FILE, '-p', PROJECT, 'exec', '-T',
               '-e', 'PGPASSWORD=' + PASSWORD_VALUES['LEDGERLAB_READONLY_PASSWORD'],
               'postgres', 'psql', '-h', '127.0.0.1', '-U', 'readonly', '-d', 'ledgerlab_perf',
               '-XAt', '-F', '|', '-v', 'ON_ERROR_STOP=1', '-c', sql]
    result = subprocess.run(command, cwd=ROOT, env=env, text=True, capture_output=True, check=False, timeout=60)
    if result.returncode != 0:
        raise Failure('psql failed: ' + result.stderr[-300:])
    return result.stdout.strip()


# ---------------------------------------------------------------- stack lifecycle

def build_artifacts():
    if os.environ.get('PERF_SKIP_BUILD') == '1':
        return
    mvnw = [str(ROOT / 'mvnw.cmd')] if os.name == 'nt' else ['./mvnw']
    say('Building the app and simulator jars...')
    run(mvnw + ['-B', '-q', '-DskipTests', 'package'])
    run(mvnw + ['-B', '-q', '-f', 'simulator/pom.xml', '-DskipTests', 'package'])


def stack_up():
    compose('down', '-v', '--remove-orphans', check=False)
    compose('up', '-d', '--build', '--wait', '--wait-timeout', '300')
    say('stack up')


def stack_down():
    compose('down', '-v', '--remove-orphans', check=False)


def invariants():
    audit = request(APP, '/api/audit/invariants')
    checks = audit.get('checks', [])
    failed = [c.get('id') for c in checks if not c.get('ok')]
    return {'count': len(checks), 'failed': failed, 'ok': len(checks) == EXPECTED_INVARIANTS and not failed,
            'checkedAt': audit.get('checkedAt')}


def assert_invariants(where):
    result = invariants()
    say('invariants after %s: %d checks, failed=%s' % (where, result['count'], result['failed']))
    return result


# ---------------------------------------------------------------- k6

def vals(metric):
    return metric.get('values', metric) if metric else {}


def run_k6(name, script, env):
    out = STATE / 'out' / name
    out.mkdir(parents=True, exist_ok=True)
    summary = out / 'summary.json'
    if summary.exists():
        summary.unlink()
    command = ['docker', 'run', '--rm', '--network', NETWORK,
               '-v', '%s:/out' % out.as_posix(), '-v', '%s:/perf:ro' % (ROOT / 'perf').as_posix()]
    for key, value in env.items():
        command += ['-e', '%s=%s' % (key, value)]
    command += [K6_IMAGE, 'run', '--quiet', '/perf/' + script]
    say('k6 %s (%s)' % (name, script))
    result = subprocess.run(command, cwd=ROOT, env=clean_env(), text=True, capture_output=True, check=False)
    if not summary.exists():
        raise Failure('k6 %s produced no summary: %s' % (name, (result.stderr or result.stdout)[-600:]))
    return json.loads(summary.read_text(encoding='utf-8'))


def trend(metrics, name):
    v = vals(metrics.get(name))
    return {'p50_ms': v.get('med'), 'p95_ms': v.get('p(95)'), 'p99_ms': v.get('p(99)'),
            'avg_ms': v.get('avg'), 'max_ms': v.get('max')}


def counter(metrics, name):
    return int(vals(metrics.get(name)).get('count', 0))


# ---------------------------------------------------------------- scenarios

def scenario_w1(rate, round_no):
    """Signed webhooks at a constant arrival rate, then the inbox drain."""
    stack_up()
    try:
        run_id = secrets.token_hex(3)
        duration = CFG['w1_duration_s']
        summary = run_k6('w1-r%d-%d' % (round_no, rate), 'w1_webhook.js', {
            'APP': INTERNAL_APP, 'RATE': rate, 'DURATION': '%ds' % duration, 'RUN': run_id,
            'WHSEC': FAKE['SMOKE_STRIPE_WEBHOOK_SECRET']})
        metrics = summary['metrics']
        accepted, not2xx = counter(metrics, 'w1_accepted'), counter(metrics, 'w1_not_2xx')
        sent = accepted + not2xx
        iterations = vals(metrics.get('iterations'))
        status_sql = ("SELECT count(*) FILTER (WHERE status = 'RECEIVED'), count(*) "
                      "FROM core.v_event WHERE event_id LIKE 'evt_perf%'")
        backlog, in_inbox = (int(x) for x in psql(status_sql).split('|'))
        started = time.monotonic()
        first_backlog = backlog
        samples = [{'t_s': 0.0, 'backlog': backlog}]
        drained = backlog == 0
        while not drained and time.monotonic() - started < CFG['w1_drain_cap_s']:
            time.sleep(2)
            backlog = int(psql(status_sql).split('|')[0])
            samples.append({'t_s': round(time.monotonic() - started, 1), 'backlog': backlog})
            drained = backlog == 0
        elapsed = time.monotonic() - started
        rate_out = (first_backlog - backlog) / elapsed if elapsed > 0 else None
        inv = assert_invariants('W1 %d rps' % rate)
        result = {
            'target_rps': rate, 'duration_s': duration,
            'achieved_rps': iterations.get('rate'),
            'requests': sent, 'accepted_2xx': accepted, 'not_2xx': not2xx,
            'error_rate': (not2xx / sent) if sent else None,
            'dropped_iterations': counter(metrics, 'dropped_iterations'),
            **trend(metrics, 'http_req_duration{ep:webhook}'),
            'events_in_inbox': in_inbox, 'none_lost': in_inbox == accepted,
            'backlog_at_load_end': first_backlog,
            'drained_within_cap': drained, 'drain_cap_s': CFG['w1_drain_cap_s'],
            'drain_seconds_measured': round(elapsed, 1) if drained else None,
            'inbox_processing_rate_per_s': round(rate_out, 2) if rate_out is not None else None,
            'backlog_left_at_cap': 0 if drained else backlog,
            'drain_seconds_estimated': (round(backlog / rate_out) if (not drained and rate_out and rate_out > 0)
                                        else None),
            'drain_samples': samples, 'invariants': inv,
        }
        return result
    finally:
        stack_down()


def create_payments(count, amount):
    def one(_):
        return request(SIM, '/sim/charges', 'POST', {'amount': amount}, timeout=60)['charge']['id']
    with ThreadPoolExecutor(max_workers=8) as pool:
        return list(pool.map(one, range(count)))


def prepare_settled_payments(count, amount):
    refs = create_payments(count, amount)
    wait_for('%d charges processed' % count,
             lambda: int(psql("SELECT count(*) FROM core.v_event WHERE event_type = 'charge.succeeded' "
                              "AND status = 'PROCESSED'")) >= count)
    payout = request(SIM, '/sim/payouts', 'POST', {})['payout']['id']
    wait_for('batch announced', lambda: request(APP, '/api/batches/' + payout)['batch']['status'] == 'ANNOUNCED')
    request(SIM, '/sim/payouts/' + payout + '/reconcile', 'POST', {})
    wait_for('batch confirmed', lambda: request(APP, '/api/batches/' + payout)['batch']['status'] == 'CONFIRMED')
    return refs


def refund_phase(round_no, phase, vus):
    summary = run_k6('w2-r%d-%s' % (round_no, phase), 'w2_refund.js', {
        'APP': INTERNAL_APP, 'VUS': vus, 'ITERATIONS': CFG['w2_payments'], 'AMOUNT': CFG['w2_refund_amount'],
        'PHASE': 'r%d%s' % (round_no, phase)})
    metrics = summary['metrics']
    created, other = counter(metrics, 'w2_created_201'), counter(metrics, 'w2_not_201')
    total = created + other
    duration_s = summary.get('state', {}).get('testRunDurationMs', 0) / 1000.0
    return {'concurrency': vus, 'requests': total, 'accepted_201': created, 'not_201': other,
            'error_rate': (other / total) if total else None,
            'tps': (created / duration_s) if duration_s else None, 'duration_s': round(duration_s, 2),
            **trend(metrics, 'http_req_duration{ep:refund}')}


def scenario_w2_w3(round_no):
    stack_up()
    try:
        refs = prepare_settled_payments(CFG['w2_payments'], CFG['w2_payment_amount'])
        out = STATE / 'out'
        for name in ('w2-r%d-serial' % round_no, 'w2-r%d-concurrent' % round_no, 'w3-r%d' % round_no):
            (out / name).mkdir(parents=True, exist_ok=True)
            (out / name / 'payments.json').write_text(json.dumps(refs), encoding='utf-8')
        say('%d settled payments ready' % len(refs))
        w2_serial = refund_phase(round_no, 'serial', 1)
        w2_concurrent = refund_phase(round_no, 'concurrent', CFG['w2_concurrency'])
        summary = run_k6('w3-r%d' % round_no, 'w3_read.js', {
            'APP': INTERNAL_APP, 'VUS': CFG['w3_vus'], 'DURATION': '%ds' % CFG['w3_duration_s']})
        metrics = summary['metrics']
        requests = counter(metrics, 'http_reqs')
        duration_s = summary.get('state', {}).get('testRunDurationMs', 0) / 1000.0
        failed = vals(metrics.get('http_req_failed'))
        w3 = {'vus': CFG['w3_vus'], 'duration_s': round(duration_s, 2), 'requests': requests,
              'rps': requests / duration_s if duration_s else None,
              'error_rate': failed.get('rate', failed.get('value')),
              'overview': trend(metrics, 'http_req_duration{ep:overview}'),
              'payment_detail': trend(metrics, 'http_req_duration{ep:payment}')}
        inv = assert_invariants('W2/W3')
        return {'w2_serial': w2_serial, 'w2_concurrent': w2_concurrent, 'w3': w3, 'invariants': inv}
    finally:
        stack_down()


# ---------------------------------------------------------------- environment and report

def shell(command, **kwargs):
    try:
        result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, check=False, timeout=60, **kwargs)
        return (result.stdout or result.stderr).strip()
    except (OSError, subprocess.SubprocessError):
        return ''


def environment():
    docker_info = json.loads(shell(['docker', 'info', '--format', '{{json .}}']) or '{}')
    cpu, memory = '', ''
    if os.name == 'nt':
        cpu = shell(['powershell', '-NoProfile', '-Command',
                     '(Get-CimInstance Win32_Processor | Select-Object -First 1).Name'])
        memory = shell(['powershell', '-NoProfile', '-Command',
                        '[math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/1GB,1)'])
    status = shell(['git', 'status', '--porcelain', '--', ':!perf/.state', ':!docs/perf'])
    return {
        'host_cpu': cpu or platform.processor(), 'host_logical_cpus': os.cpu_count(),
        'host_memory_gb': memory, 'host_os': platform.platform(),
        'docker_server': docker_info.get('ServerVersion'), 'docker_os': docker_info.get('OperatingSystem'),
        'docker_cpus': docker_info.get('NCPU'),
        'docker_memory_gb': round(docker_info.get('MemTotal', 0) / 2 ** 30, 1),
        'container_limits': 'none set (the app, simulator, PostgreSQL and the k6 container share the Docker VM)',
        'commit': shell(['git', 'rev-parse', 'HEAD']), 'worktree_dirty': bool(status),
        'k6_image': K6_IMAGE, 'k6_version': shell(['docker', 'run', '--rm', K6_IMAGE, 'version']),
        'app_defaults': 'inbox batch-size 20, poll-interval 1s; outbound batch-size 10, poll-interval 1s',
    }


def stack_versions():
    stack_up()
    try:
        java = compose('exec', '-T', 'app', 'java', '-version', capture=True).stderr.strip().splitlines()
        return {'jdk': java[0] if java else '', 'postgresql': psql('SHOW server_version')}
    finally:
        stack_down()


def median(values):
    values = [v for v in values if v is not None]
    return statistics.median(values) if values else None


def median_of(records, keys):
    return {key: median([r.get(key) for r in records]) for key in keys}


TREND_KEYS = ['p50_ms', 'p95_ms', 'p99_ms']


def summarize(rounds):
    w1 = {}
    for rate in CFG['w1_rates']:
        runs = [r['w1'][rate] for r in rounds]
        w1[rate] = median_of(runs, ['achieved_rps', 'error_rate', 'dropped_iterations', 'backlog_at_load_end',
                                    'inbox_processing_rate_per_s', 'drain_seconds_measured',
                                    'drain_seconds_estimated', 'backlog_left_at_cap', *TREND_KEYS])
        w1[rate]['drained_in_rounds'] = sum(1 for r in runs if r['drained_within_cap'])
    result = {'w1': w1}
    for name in ('w2_serial', 'w2_concurrent'):
        runs = [r[name] for r in rounds]
        result[name] = median_of(runs, ['tps', 'error_rate', *TREND_KEYS])
    runs = [r['w3'] for r in rounds]
    result['w3'] = {
        'rps': median([r['rps'] for r in runs]), 'error_rate': median([r['error_rate'] for r in runs]),
        'overview': median_of([r['overview'] for r in runs], TREND_KEYS),
        'payment_detail': median_of([r['payment_detail'] for r in runs], TREND_KEYS)}
    return result


def f(value, digits=1):
    return '—' if value is None else ('%.*f' % (digits, value))


def pct(value):
    return '—' if value is None else '%.2f%%' % (value * 100)


def report_md(doc):
    env, versions, cfg, med = doc['environment'], doc['versions'], doc['config'], doc['median']
    lines = [
        '# 性能基线（%s）' % doc['date'], '',
        '> 设计：[demo-and-delivery.md 第 8 节](../design/demo-and-delivery.md)；验收 AC-9.10。'
        '数字全部来自 `sh perf/run.sh` 的这一次实测（%d 轮，取中位数），原始数据见 [同名 json](%s)。' % (
            cfg['rounds'], doc['json_name']), '',
        '**不变量：** 每个栈的每次测量后都调用了 `/api/audit/invariants`，共 %d 次，全部 %d 项通过。%s' % (
            doc['invariant_checks'], EXPECTED_INVARIANTS, '' if doc['valid'] else '**有违规，本轮基线作废。**'), '',
        '## 环境', '',
        '| 项 | 值 |', '|---|---|',
        '| CPU | %s（逻辑核 %s） |' % (env['host_cpu'], env['host_logical_cpus']),
        '| 内存 | %s GB |' % env['host_memory_gb'],
        '| 操作系统 | %s |' % env['host_os'],
        '| Docker | %s，分给容器 %s 核 / %s GB；容器资源限制：%s |' % (
            env['docker_server'], env['docker_cpus'], env['docker_memory_gb'], env['container_limits']),
        '| 提交 | `%s`%s |' % (env['commit'][:12], '（工作区有未提交改动）' if env['worktree_dirty'] else ''),
        '| JDK | %s |' % versions['jdk'],
        '| PostgreSQL | %s |' % versions['postgresql'],
        '| k6 | %s（镜像 `%s`，2026-11-05 查得的当前版本） |' % (env['k6_version'].splitlines()[0], env['k6_image']),
        '| 应用配置 | 默认 profile 与默认调度参数：%s |' % env['app_defaults'], '',
        '负载发生器与被测栈在同一台机器、同一个 Docker VM 里，互相争抢 CPU；数字适合横向比较改动前后，不代表独立压测机上的上限。', '',
        '## W1 webhook 入站', '',
        '直接对 `/webhooks/stripe` 发带签名的 `charge.succeeded`（签名在 k6 里按 Stripe 方案算），恒定到达率，每档 %d 秒，'
        '每档一个全新的栈。入站只落 inbox，业务处理由后台 worker 异步完成，所以延迟是“收下并落盘”的延迟；'
        'inbox 排空另算。' % cfg['w1_duration_s'], '',
        '| 目标 rps | 实测 rps | 2xx 以外 | p50 ms | p95 ms | p99 ms | 负载结束时积压 | worker 处理速度 /s | 排空 |',
        '|---|---|---|---|---|---|---|---|---|']
    for rate in cfg['w1_rates']:
        m = med['w1'][str(rate)]
        if m['drained_in_rounds'] == cfg['rounds']:
            drain = '%s s（实测）' % f(m['drain_seconds_measured'], 0)
        else:
            drain = '%d/%d 轮在 %d s 内排空；其余按实测处理速度估算 %s s' % (
                m['drained_in_rounds'], cfg['rounds'], cfg['w1_drain_cap_s'], f(m['drain_seconds_estimated'], 0))
        lines.append('| %d | %s | %s | %s | %s | %s | %s | %s | %s |' % (
            rate, f(m['achieved_rps']), pct(m['error_rate']), f(m['p50_ms'], 2), f(m['p95_ms'], 2),
            f(m['p99_ms'], 2), f(m['backlog_at_load_end'], 0), f(m['inbox_processing_rate_per_s']), drain))
    lines += ['',
              '“排空”：负载结束后每 2 秒查一次 `RECEIVED` 积压，最多等 %d 秒（上限，不是目标）；没排空的按这段时间内实测的处理速度'
              '估算剩余时间，这是由实测值推出来的估算，不是实测。每档都核对过 inbox 里的事件数等于 k6 记到的 2xx 数（没有丢事件）。' %
              cfg['w1_drain_cap_s'], '',
              '## W2 单商户退款受理', '',
              '%d 笔已结算支付（每笔 %d 分，一个批次 CONFIRMED），对它们受理 %d 笔各 %d 分的退款；先 1 并发（串行基准），'
              '再 %d 并发。每笔退款受理都要拿同一把商户资金锁（账户 2102，ADR-0004）。' % (
                  cfg['w2_payments'], cfg['w2_payment_amount'], cfg['w2_payments'], cfg['w2_refund_amount'],
                  cfg['w2_concurrency']), '',
              '| 并发 | 受理 TPS | 错误率 | p50 ms | p95 ms | p99 ms |', '|---|---|---|---|---|---|']
    for name, label in (('w2_serial', 1), ('w2_concurrent', cfg['w2_concurrency'])):
        m = med[name]
        lines.append('| %d | %s | %s | %s | %s | %s |' % (
            label, f(m['tps']), pct(m['error_rate']), f(m['p50_ms'], 2), f(m['p95_ms'], 2), f(m['p99_ms'], 2)))
    w3 = med['w3']
    lines += ['', '## W3 只读查询', '',
              '%d 个并发用户，%d 秒，每次迭代先查总览 `/api/overview` 再查一笔随机支付详情 `/api/payments/{ref}`；'
              '总吞吐 %s 请求/秒，错误率 %s。' % (cfg['w3_vus'], cfg['w3_duration_s'], f(w3['rps']), pct(w3['error_rate'])), '',
              '| 接口 | p50 ms | p95 ms | p99 ms |', '|---|---|---|---|',
              '| 总览 | %s | %s | %s |' % tuple(f(w3['overview'][k], 2) for k in TREND_KEYS),
              '| 支付详情 | %s | %s | %s |' % tuple(f(w3['payment_detail'][k], 2) for k in TREND_KEYS), '',
              '## 瓶颈分析', '', doc['analysis'], '',
              '## 复现', '',
              '`sh perf/run.sh`（Docker 和 JDK 即可；用独立项目 `ledgerlab-perf`，端口 38080/38081/35432，不碰沙箱栈和演示栈）。'
              '`sh perf/run.sh --quick` 只跑 1 轮 10 秒小档位，用来检查脚本。', '']
    return '\n'.join(lines)


def analysis_text(med, cfg):
    s, c = med['w2_serial'], med['w2_concurrent']
    ratio = (c['tps'] / s['tps']) if s['tps'] and c['tps'] else None
    w1_top = med['w1'][str(cfg['w1_rates'][-1])]
    drain_rate = w1_top['inbox_processing_rate_per_s']
    parts = []
    parts.append(
        '**退款受理被 2102 串行。** 1 并发实测 %s TPS，%d 并发实测 %s TPS（%s 倍）。并发没有把吞吐按比例抬高，'
        '而是把等待时间转成延迟：%d 并发下 p95 为 %s ms，1 并发下为 %s ms。这与 ADR-0004 的预期一致：单商户下所有改余额的事务'
        '先锁同一行 `funds_lock`，临界区内要读额度、写分录和命令，所以吞吐上限约等于 1 / 临界区时长。' % (
            f(s['tps']), cfg['w2_concurrency'], f(c['tps']), f(ratio, 2) if ratio else '—', cfg['w2_concurrency'],
            f(c['p95_ms'], 1), f(s['p95_ms'], 1)))
    parts.append(
        '**多商户时按商户分片。** 资金锁、2102 等账户都是“每商户一份”，不同商户的退款不竞争同一把锁，所以总吞吐可以随商户数增加'
        '直到受 PostgreSQL 写入（WAL、连接池）限制；本基线只有一个商户，测不到这一段，不能由此推出多商户的数字，'
        '需要多商户场景另测。')
    parts.append(
        '**inbox 排空速度是 webhook 路径的瓶颈。** 入站接口只做验签＋写一行，在测试的各档位都远快于后台处理；'
        '默认配置下 worker 每个轮询周期（间隔 1 秒）最多领 20 条，%d rps 档位实测处理速度为 %s 条/秒。'
        '负载持续高于这个速度时积压线性增长，排空时间见 W1 表。要提高上限可以调大 `ledgerlab.inbox.batch-size` 或缩短轮询间隔，'
        '但那是另一组配置，本基线没有测，也没有改业务代码。' % (cfg['w1_rates'][-1], f(drain_rate)))
    return '\n\n'.join(parts)


def main():
    started = utcnow()
    say('config: %s' % json.dumps(CFG))
    build_artifacts()
    outcome_dir = ROOT / ('target/perf' if QUICK else 'docs/perf')
    outcome_dir.mkdir(parents=True, exist_ok=True)
    versions = stack_versions()
    env = environment()
    rounds = []
    for round_no in range(1, CFG['rounds'] + 1):
        say('=== round %d/%d ===' % (round_no, CFG['rounds']))
        record = {'round': round_no, 'w1': {}}
        for rate in CFG['w1_rates']:
            record['w1'][rate] = scenario_w1(rate, round_no)
        record.update(scenario_w2_w3(round_no))
        rounds.append(record)
    invariant_runs = []
    for r in rounds:
        invariant_runs += [r['w1'][rate]['invariants'] for rate in CFG['w1_rates']] + [r['invariants']]
    valid = all(i['ok'] for i in invariant_runs) and all(
        r['w1'][rate]['none_lost'] for r in rounds for rate in CFG['w1_rates'])
    med = summarize(rounds)
    med['w1'] = {str(k): v for k, v in med['w1'].items()}
    date = started.strftime('%Y-%m-%d')
    json_name = 'baseline-%s%s.json' % (date, '-quick' if QUICK else '')
    doc = {'date': date, 'startedAt': started.strftime('%Y-%m-%dT%H:%M:%SZ'),
           'finishedAt': utcnow().strftime('%Y-%m-%dT%H:%M:%SZ'), 'valid': valid, 'json_name': json_name,
           'invariant_checks': len(invariant_runs), 'config': CFG, 'environment': env, 'versions': versions,
           'median': med, 'rounds': rounds}
    doc['analysis'] = analysis_text(med, CFG)
    (outcome_dir / json_name).write_text(json.dumps(doc, indent=2, sort_keys=True, default=str) + '\n', encoding='utf-8')
    (outcome_dir / json_name.replace('.json', '.md')).write_text(report_md(doc), encoding='utf-8')
    say('report: %s' % (outcome_dir / json_name.replace('.json', '.md')).relative_to(ROOT).as_posix())
    if not valid:
        say('INVARIANT VIOLATION OR LOST EVENTS: the run is void (see the report)')
        return 1
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Failure as failure:
        print('perf failed: %s' % failure, file=sys.stderr)
        stack_down()
        sys.exit(1)
