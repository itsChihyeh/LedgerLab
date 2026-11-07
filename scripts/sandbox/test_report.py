"""AC-8.10 offline checks for the run report's pure functions."""
import json
import tempfile
import unittest
from pathlib import Path

import report as r
from runtime import Failure

TABLE = """### 9.9 已知差异

| 项目 | fake Stripe | 真实 Stripe 测试模式 |
|---|---|---|
| 国际卡手续费 | 国内公式 | 多 10 分 \\| 不推广 |
| 资金可用时间 | 立即 | 待结算 |
"""


def result(name, ac, outcome, finished, **extra):
    return dict({'_file': name, 'ac': ac, 'result': outcome, 'finished_at': finished, 'run_id': 'sbx_t'}, **extra)


class ReportTests(unittest.TestCase):
    def test_ac_8_10_distribution_counts_each_status(self):
        self.assertEqual({'a': 2, 'b': 1}, r.distribution([{'s': 'a'}, {'s': 'b'}, {'s': 'a'}], lambda x: x['s']))
        self.assertEqual({}, r.distribution([], lambda x: x))

    def test_ac_8_10_latest_acceptance_per_ac_keeps_history_and_ignores_other_runs(self):
        rows = [result('accept-1.json', 'AC-8.5', 'FAIL', '2026-11-03T01:00:00Z', resend_count=6),
                result('accept-2.json', 'AC-8.5', 'PASS', '2026-11-03T02:00:00Z', resend_count=12,
                       invariants={'violations': []}),
                dict(result('accept-3.json', 'AC-8.5', 'FAIL', '2026-11-04T00:00:00Z'), run_id='sbx_other'),
                result('catchup-1.json', 'AC-8.5', 'FAIL', '2026-11-05T00:00:00Z')]
        summary = r.acceptance_summary(rows, 'sbx_t')
        self.assertEqual('PASS', summary['AC-8.5']['result'])
        self.assertEqual(['FAIL', 'PASS'], summary['AC-8.5']['history'])
        self.assertEqual(0, summary['AC-8.5']['invariant_violations'])
        self.assertEqual('NOT_RUN', summary['AC-8.4']['result'])

    def test_ac_8_10_resend_totals_include_catchup_and_acceptance_and_name_missing_events(self):
        rows = [result('catchup-1.json', 'AC-8.5', 'PASS', '2026-11-03T01:00:00Z', resend_count=1,
                       initial_missing_count=1, missing_count=0, recovered_event_ids=['evt_a']),
                result('catchup-2.json', 'AC-8.5', 'MISSING', '2026-11-04T01:00:00Z', resend_count=2,
                       initial_missing_count=2, missing_count=1, missing_events=[{'id': 'evt_b'}]),
                result('accept-1.json', 'AC-8.5', 'PASS', '2026-11-03T02:00:00Z', resend_count=12)]
        summary = r.resend_summary(rows)
        self.assertEqual(15, summary['resend_total'])
        self.assertEqual(2, summary['events_recovered'])
        self.assertEqual(['evt_a'], summary['recovered_event_ids'])
        self.assertEqual(['evt_b'], summary['still_missing_event_ids'])

    def test_ac_8_10_probe_and_listener_recovery_resends_are_counted_once(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for name, accepted in (('resend-1.json', True), ('resend-2.json', False)):
                (directory / name).write_text(json.dumps({'cli_json_accepted': accepted}), encoding='utf-8')
            (directory / 't04-listener-recovery.json').write_text(json.dumps(
                {'events': [{'cli_json_accepted': True}, {'cli_json_accepted': True}, {'cli_json_accepted': False}]}),
                encoding='utf-8')
            extra = r.extra_resends(directory)
        self.assertEqual({'probe': 1, 'listener_recovery': 2}, extra)
        rows = [result('accept-1.json', 'AC-8.5', 'PASS', 'x', resend_count=12)]
        self.assertEqual(15, r.resend_summary(rows, extra)['resend_total'])

    def test_ac_8_10_invariant_violations_are_counted_and_malformed_results_fail(self):
        rows = [result('a.json', 'AC-8.4', 'PASS', 'x', invariants={'violations': []}),
                result('b.json', 'AC-8.4', 'FAIL', 'y', invariants={'violations': [{'invariant': 'INV-1'}]})]
        self.assertEqual({'checks_in_saved_results': 2, 'violations_in_saved_results': 1}, r.invariant_history(rows))
        with self.assertRaises(Failure):
            r.count_violations({'violations': 'none'})

    def test_ac_8_10_known_differences_come_from_design_table(self):
        rows = r.known_differences(TABLE)
        self.assertEqual(['国际卡手续费', '资金可用时间'], [x['item'] for x in rows])
        self.assertEqual('多 10 分 | 不推广', rows[0]['real'])
        with self.assertRaises(Failure):
            r.known_differences('no such section')

    def test_ac_8_10_real_design_table_is_parseable(self):
        rows = r.known_differences(r.DESIGN.read_text(encoding='utf-8'))
        self.assertGreaterEqual(len(rows), 4)
        self.assertTrue(all(x['fake'] and x['real'] for x in rows))

    def test_ac_8_10_scan_rejects_secrets_accounts_and_emails(self):
        for text in ('rk_' + 'test_x', 'whsec_' + 'x', 'acct_' + '1Abc', 'a' + '@' + 'b.example', 'x_secret_y'):
            with self.subTest(text=text), self.assertRaises(Failure):
                r.scan_report('safe', text)
        r.scan_report('evt_123 po_456 ch_789 txn_1 acct_' + 'REDACTED')

    def test_ac_8_9_fee_analysis_section_lists_rule_and_unexplained_payments(self):
        analysis = {'count': 2, 'rows': [
            {'charge': 'ch_1', 'country': 'US', 'brand': 'visa', 'amount': 690, 'stripe_fee': 56, 'local_fee': 50,
             'difference': 6}],
            'by_country': [{'country': 'US', 'count': 1, 'brands': ['visa'],
                            'stripe_fee_rule': {'fixed': 30, 'bps': 370}, 'unexplained': []},
                           {'country': 'FR', 'count': 1, 'brands': ['visa'], 'stripe_fee_rule': None,
                            'unexplained': ['ch_9']}]}
        text = '\n'.join(r.fee_section(analysis))
        self.assertIn('30 + round(3.7%)', text)
        self.assertIn('ch_9', text)
        self.assertIn('未运行', '\n'.join(r.fee_section(None)))

    def test_ac_8_9_missing_fee_analysis_file_is_none_and_malformed_fails(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.assertIsNone(r.load_fee_analysis(directory))
            (directory / 'fee-analysis.json').write_text('{"rows": 1, "by_country": []}', encoding='utf-8')
            with self.assertRaises(Failure):
                r.load_fee_analysis(directory)

    def test_ac_8_10_load_results_keeps_only_acceptance_shaped_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / 'accept-1.json').write_text(json.dumps(
                {'ac': 'AC-8.4', 'result': 'PASS', 'finished_at': 'x'}), encoding='utf-8')
            (directory / 'traffic-1.json').write_text('{"payments": []}', encoding='utf-8')
            self.assertEqual(['accept-1.json'], [x['_file'] for x in r.load_results(directory)])
            (directory / 'broken.json').write_text('{', encoding='utf-8')
            with self.assertRaises(Failure):
                r.load_results(directory)


if __name__ == '__main__':
    unittest.main()
