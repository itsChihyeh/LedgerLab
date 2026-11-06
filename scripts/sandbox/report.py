"""AC-8.10: run report from public HTTP interfaces, Stripe read-only lists and saved acceptance results.

Writes docs/runs/sandbox-<run_id>.md and .json. Nothing is written unless the secret scan passes.
"""
import collections
import json
import re
import sys
from datetime import datetime

from fixtures import UNSAFE
from payout_refund_recon import list_all
from runtime import APP, OUT, ROOT, SIM, Failure, emit, get_json, instant, load_run, require, write_json

DESIGN = ROOT / 'docs/design/stripe-integration.md'
RUNS = ROOT / 'docs/runs'
AC_ORDER = ('AC-8.4', 'AC-8.5', 'AC-8.6', 'AC-8.8', 'AC-8.9')
ACCEPT_KEYS = ('captured_count', 'generated_count', 'finding_count', 'replay_identical', 'resend_count',
               'stripe_refund_statuses', 'pending_observed', 'untraceable', 'reason')


def distribution(items, key):
    return dict(sorted(collections.Counter(str(key(item)) for item in items).items()))


def epoch(text):
    return int(datetime.fromisoformat(text.replace('Z', '+00:00')).timestamp())


def count_violations(invariants):
    require(isinstance(invariants, dict) and isinstance(invariants.get('violations'), list), 'INVALID_INVARIANTS')
    return len(invariants['violations'])


# ---------------------------------------------------------------- saved acceptance results

def load_results(directory):
    results = []
    for path in sorted(directory.glob('*.json')):
        try:
            value = json.loads(path.read_text(encoding='utf-8'))
        except (ValueError, OSError):
            raise Failure('INVALID_SAVED_RESULT') from None
        if isinstance(value, dict) and value.get('ac') and value.get('finished_at'):
            results.append(dict(value, _file=path.name))
    return results


def acceptance_summary(results, run_id):
    """Latest result per AC plus its history. Only this run's results count."""
    mine = [r for r in results if r.get('run_id') in (None, run_id) and r['_file'].startswith('accept-')]
    summary = {}
    for ac in AC_ORDER:
        rows = sorted((r for r in mine if r['ac'] == ac), key=lambda r: r['finished_at'])
        if not rows:
            summary[ac] = {'result': 'NOT_RUN', 'history': []}
            continue
        last = rows[-1]
        facts = {key: last[key] for key in ACCEPT_KEYS if key in last}
        summary[ac] = {'result': last['result'], 'finished_at': last['finished_at'], 'file': last['_file'],
                       'facts': facts, 'history': [r['result'] for r in rows]}
        if 'invariants' in last:
            summary[ac]['invariant_violations'] = count_violations(last['invariants'])
    return summary


def extra_resends(directory):
    """Resends recorded outside acceptance results: single-event probes and the listener recovery."""
    probes = recovery = 0
    for path in sorted(directory.glob('resend-*.json')):
        probes += 1 if json.loads(path.read_text(encoding='utf-8')).get('cli_json_accepted') is True else 0
    for path in sorted(directory.glob('*listener-recovery.json')):
        events = json.loads(path.read_text(encoding='utf-8')).get('events', [])
        recovery += sum(1 for e in events if e.get('cli_json_accepted') is True)
    return {'probe': probes, 'listener_recovery': recovery}


def resend_summary(results, extra=None):
    """Every `stripe events resend` this run: acceptance duplicates, catch-up and recovery resends."""
    extra = extra or {'probe': 0, 'listener_recovery': 0}
    catchups = [r for r in results if r['_file'].startswith('catchup-')]
    accepts = [r for r in results if r['_file'].startswith('accept-') and r['ac'] == 'AC-8.5']
    last = max(catchups, key=lambda r: r['finished_at'], default=None)
    recovered = sorted({i for r in catchups for i in r.get('recovered_event_ids', [])})
    return {
        'resend_total': sum(r.get('resend_count', 0) for r in catchups + accepts)
        + extra['probe'] + extra['listener_recovery'],
        'probe_resends': extra['probe'],
        'listener_recovery_resends': extra['listener_recovery'],
        'catchup_resends': sum(r.get('resend_count', 0) for r in catchups),
        'acceptance_resends': sum(r.get('resend_count', 0) for r in accepts),
        'catchup_runs': len(catchups),
        'events_recovered': sum(max(0, r.get('initial_missing_count', 0) - r.get('missing_count', 0))
                                for r in catchups),
        'recovered_event_ids': recovered,
        'still_missing_event_ids': [e['id'] if isinstance(e, dict) else e
                                    for e in (last or {}).get('missing_events', [])],
        'latest_catchup': (last or {}).get('finished_at'),
    }


def invariant_history(results):
    checked = [(r['_file'], count_violations(r['invariants'])) for r in results if 'invariants' in r]
    return {'checks_in_saved_results': len(checked),
            'violations_in_saved_results': sum(count for _, count in checked)}


# ---------------------------------------------------------------- known differences (design 9.9)

def known_differences(markdown):
    section = markdown.split('### 9.9', 1)
    require(len(section) == 2, 'KNOWN_DIFFERENCES_MISSING')
    rows = []
    for line in section[1].splitlines():
        cells = [c.strip().replace('\\|', '|') for c in re.split(r'(?<!\\)\|', line.strip().strip('|'))]
        if line.startswith('|') and len(cells) == 3 and not set(cells[0]) <= set('-: '):
            rows.append({'item': cells[0], 'fake': cells[1], 'real': cells[2]})
    require(rows and rows[0]['item'] == '项目', 'KNOWN_DIFFERENCES_MISSING')
    return rows[1:]


# ---------------------------------------------------------------- scan and render

def scan_report(*texts):
    for text in texts:
        require(not UNSAFE.search(text), 'UNSAFE_REPORT_CONTENT')


def table(rows, headers):
    lines = ['| ' + ' | '.join(headers) + ' |', '|' + '---|' * len(headers)]
    lines.extend('| ' + ' | '.join(str(c).replace('|', '\\|') for c in row) + ' |' for row in rows)
    return '\n'.join(lines)


def dist_text(counts):
    return '，'.join(f'{k} {v}' for k, v in counts.items()) or '无'


def load_fee_analysis(directory):
    """Per-payment FEE_MISMATCH explanation saved by fee-analysis.sh; None when it was not run."""
    path = directory / 'fee-analysis.json'
    if not path.exists():
        return None
    value = json.loads(path.read_text(encoding='utf-8'))
    require(isinstance(value.get('rows'), list) and isinstance(value.get('by_country'), list), 'FEE_ANALYSIS_MALFORMED')
    keep = ('charge', 'country', 'brand', 'amount', 'stripe_fee', 'local_fee', 'difference')
    return {'count': value['count'], 'by_country': value['by_country'],
            'rows': [{k: row[k] for k in keep} for row in value['rows']]}


def fee_rule_text(group):
    rule = group['stripe_fee_rule']
    return f"{rule['fixed']} + round({rule['bps'] / 100:g}%)" if rule else '无法归入固定＋比例规则'


def fee_section(analysis):
    if analysis is None:
        return ['## 手续费差异逐笔说明', '', '未运行 `fee-analysis.sh`。', '']
    rules = [[g['country'], g['count'], ','.join(g['brands']), fee_rule_text(g), ','.join(g['unexplained']) or '无']
             for g in analysis['by_country']]
    rows = [[x['charge'], x['country'], x['brand'], x['amount'], x['stripe_fee'], x['local_fee'], x['difference']]
            for x in analysis['rows']]
    return ['## 手续费差异逐笔说明（AC-8.9）', '',
            f"`FEE_MISMATCH` 共 {analysis['count']} 笔，逐笔取自 Stripe 只读接口（卡只记国家和品牌）。"
            '“本地”是国内公式 30 + round(2.9%)；本地手续费模型未改。', '',
            table(rules, ['卡国家', '笔数', '品牌', 'Stripe 实测规则', '归不进规则的支付']), '',
            table(rows, ['支付', '卡国家', '品牌', '金额（分）', 'Stripe 手续费', '本地手续费', '差额']), '']


def render(report):
    s, loc, rel = report['stripe'], report['local'], report['relay']
    out = [f"# 沙箱运行报告 {report['run_id']}", '',
           f"生成时间 {report['generated_at']}；窗口起点 {report['window_start']}。"
           '数据来自 app 与 simulator 的公开 HTTP 接口、Stripe 只读列表和已保存的验收结果；README 和对外材料只引用本报告的数字。',
           '', '## 两边对账：Stripe 与本地', '',
           table([['支付（charge）', s['payments']['count'], dist_text(s['payments']['by_status']),
                   loc['payments']['count'], dist_text(loc['payments']['by_status'])],
                  ['退款', s['refunds']['count'], dist_text(s['refunds']['by_status']),
                   loc['refunds']['count'], dist_text(loc['refunds']['by_status'])],
                  ['payout／批次', s['payouts']['count'], dist_text(s['payouts']['by_status']),
                   loc['batches']['count'], dist_text(loc['batches']['by_status'])]],
                 ['对象', 'Stripe 数量', 'Stripe 状态', '本地数量', '本地状态']), '',
           f"- 支付编号两边差集：Stripe 有而本地无 {len(report['comparison']['payments_only_in_stripe'])} 笔，"
           f"本地有而 Stripe 无 {len(report['comparison']['payments_only_local'])} 笔。",
           f"- payout 金额：Stripe {s['payouts']['amount_total']} 分，本地批次净额 {loc['batches']['net_total']} 分。",
           f"- 支付金额合计：Stripe {s['payments']['amount_total']} 分；退款合计 {s['refunds']['amount_total']} 分。",
           '', '## 故障注入（chaos relay）', '',
           table([['入站（Stripe → app）', k, v] for k, v in rel['inbound'].items()]
                 + [['出站（app → Stripe）', k, v] for k, v in rel['outbound'].items()], ['方向', '动作', '次数']), '',
           f"统计起点 {rel['since']}。", '', '## 漏收与补投', '',
           f"- `stripe events resend` 共 {report['resend']['resend_total']} 次"
           f"（补投脚本 {report['resend']['catchup_resends']}，验收的重复投递 {report['resend']['acceptance_resends']}，"
           f"单事件探测 {report['resend']['probe_resends']}，监听中断后的恢复 {report['resend']['listener_recovery_resends']}）。",
           f"- 补投脚本共跑 {report['resend']['catchup_runs']} 次，补回 {report['resend']['events_recovered']} 个漏收事件；"
           f"最近一次 {report['resend']['latest_catchup']}。",
           f"- 仍缺失事件：{len(report['resend']['still_missing_event_ids'])} 个"
           + (f"（{', '.join(report['resend']['still_missing_event_ids'])}）"
              if report['resend']['still_missing_event_ids'] else '。'),
           '', '## 不变量', '',
           f"- 现在检查（审计接口，{report['invariants']['checked_at']}）：违反 {report['invariants']['violations_now']} 次。",
           f"- 验收结果里保存的 {report['invariants']['checks_in_saved_results']} 次检查：违反合计 "
           f"{report['invariants']['violations_in_saved_results']} 次。",
           f"- 结论：{'全部成立' if report['invariants']['all_hold'] else '存在违反，运行不通过'}。",
           '', '## 对账发现', '',
           f"- 开放问题（现在）：{dist_text(report['reconciliation']['open_by_category'])}。",
           f"- AC-8.9 最近一次：发现 {report['reconciliation']['acceptance_finding_count']} 条，"
           f"其中追不到 Stripe 编号 {report['reconciliation']['untraceable']} 条，"
           f"重放一致 {report['reconciliation']['replay_identical']}。", '',
           *fee_section(report.get('fee_analysis')),
           '## 各 AC 最近一次验收', '',
           table([[ac, v['result'], v.get('finished_at', '—'), ' → '.join(v['history']) or '—']
                  for ac, v in report['acceptance'].items()], ['AC', '结果', '完成时间', '历次结果（旧 → 新）']), '',
           '## 夹具场景（AC-8.3，CI 契约测试）', '',
           table([[x['scenario'], x['files'], x['status']] for x in report['fixtures']], ['场景', '文件数', '状态']), '',
           '## fake 与真实 Stripe 的已知差异', '',
           table([[d['item'], d['fake'], d['real']] for d in report['known_differences']],
                 ['项目', 'fake Stripe', '真实 Stripe 测试模式']), '']
    return '\n'.join(out)


# ---------------------------------------------------------------- collection

def fixtures_status():
    base = ROOT / 'src/test/resources/contracts/stripe-sandbox'
    rows = []
    for key, name in (('s1', 's1-payment'), ('s2', 's2-refund'), ('s3', 's3-refund-pending'), ('s4', 's4-payout')):
        manifest = base / name / 'manifest.json'
        if manifest.exists():
            files = json.loads(manifest.read_text(encoding='utf-8'))['files']
            rows.append({'scenario': key + ' ' + name, 'files': len(files), 'status': '已录制'})
        else:
            rows.append({'scenario': key + ' ' + name, 'files': 0, 'status': '测试模式未复现（不录）'})
    return rows


def collect(run, results):
    start = epoch(run['window_start'])
    window = [('created[gte]', start)]
    charges = list_all('/v1/charges', 'ch', window)
    refunds = list_all('/v1/refunds', 're', window)
    payouts = list_all('/v1/payouts', 'po', window)
    payments = get_json(APP, '/api/payments?limit=100')['payments']
    require(len(payments) < 100, 'LOCAL_PAYMENTS_TRUNCATED')
    batches = get_json(APP, '/api/batches?limit=100')['batches']
    require(len(batches) < 100, 'LOCAL_BATCHES_TRUNCATED')
    local_refunds = []
    for refund in refunds:
        local_id = (refund.get('metadata') or {}).get('ledgerlab_refund_id')
        found = get_json(APP, '/api/refunds/' + local_id, missing_ok=True) if local_id else None
        local_refunds.append(found)
    stripe_ids = {c['id'] for c in charges}
    local_ids = {p['processorPaymentRef'] for p in payments}
    since = run['window_start']
    stats = get_json(SIM, '/sim/relay/stats?since=' + since)
    invariants_now = get_json(APP, '/api/audit/invariants')
    issues = get_json(APP, '/api/recon/issues?status=OPEN&limit=100')['issues']
    require(len(issues) < 100, 'RECON_ISSUES_TRUNCATED')
    acceptance = acceptance_summary(results, run['run_id'])
    recon = acceptance['AC-8.9'].get('facts', {})
    history = invariant_history(results)
    violations_now = count_violations(invariants_now)
    return {
        'run_id': run['run_id'], 'window_start': since, 'generated_at': instant(),
        'stripe': {
            'payments': {'count': len(charges), 'by_status': distribution(charges, lambda c: c['status']),
                         'amount_total': sum(c['amount'] for c in charges)},
            'refunds': {'count': len(refunds), 'by_status': distribution(refunds, lambda r: r['status']),
                        'amount_total': sum(r['amount'] for r in refunds)},
            'payouts': {'count': len(payouts), 'by_status': distribution(payouts, lambda p: p['status']),
                        'amount_total': sum(p['amount'] for p in payouts)}},
        'local': {
            'payments': {'count': len(payments), 'by_status': distribution(payments, lambda p: p['status'])},
            'refunds': {'count': sum(1 for x in local_refunds if x),
                        'by_status': distribution([x for x in local_refunds if x], lambda x: x['refund']['status']),
                        'missing_locally': sum(1 for x in local_refunds if not x)},
            'batches': {'count': len(batches), 'by_status': distribution(batches, lambda b: b['status']),
                        'net_total': sum(b['net'] for b in batches)}},
        'comparison': {'payments_only_in_stripe': sorted(stripe_ids - local_ids),
                       'payments_only_local': sorted(local_ids - stripe_ids)},
        'relay': {'since': since, 'inbound': stats['inbound'], 'outbound': stats['outbound']},
        'resend': resend_summary(results, extra_resends(OUT)),
        'invariants': {'checked_at': invariants_now['checkedAt'], 'violations_now': violations_now,
                       **history, 'all_hold': violations_now == 0 and history['violations_in_saved_results'] == 0},
        'reconciliation': {
            'open_by_category': distribution([i for i in issues if i.get('status') == 'OPEN'],
                                             lambda i: i['category']),
            'acceptance_finding_count': recon.get('finding_count'),
            'untraceable': len(recon.get('untraceable', [])),
            'replay_identical': recon.get('replay_identical')},
        'fee_analysis': load_fee_analysis(OUT),
        'acceptance': acceptance,
        'fixtures': fixtures_status(),
        'known_differences': known_differences(DESIGN.read_text(encoding='utf-8')),
    }


def main():
    try:
        run = load_run()
        report = collect(run, load_results(OUT))
        text = render(report)
        # Scan the exact bytes that would be committed before touching the output directory.
        scan_report(text, json.dumps(report, ensure_ascii=False))
        stem = RUNS / ('sandbox-' + run['run_id'])
        write_json(stem.with_suffix('.json'), report)
        with stem.with_suffix('.md').open('w', encoding='utf-8', newline='\n') as stream:
            stream.write(text)
        emit({'result': 'PASS' if report['invariants']['all_hold'] else 'FAIL',
              'report': stem.with_suffix('.md').relative_to(ROOT).as_posix(),
              'invariant_violations': report['invariants']['violations_now']})
        return 0 if report['invariants']['all_hold'] else 1
    except Failure as failure:
        emit({'result': 'FAIL', 'reason': str(failure)})
        return failure.exit_code
    except (ValueError, KeyError, TypeError, OSError):
        emit({'result': 'FAIL', 'reason': 'REPORT_FAILED'})
        return 2


if __name__ == '__main__':
    sys.exit(main())
