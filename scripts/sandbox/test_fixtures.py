import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import fixtures as f
import check_payout as p
from runtime import Failure


class FixtureTests(unittest.TestCase):
    def webhook(self):
        return {'recorded_at': '2026-11-02T12:00:00Z', 'body': {
            'id': 'evt_synthetic', 'type': 'charge.succeeded', 'api_version': f.VERSION,
            'created': 1793617200, 'livemode': False, 'data': {'object': {
                'id': 'ch_synthetic', 'payment_intent': 'pi_synthetic', 'amount': 1234,
                'amount_captured': 1234, 'currency': 'cad', 'captured': True,
                'status': 'succeeded', 'livemode': False, 'created': 1793617200,
                'metadata': {'ledgerlab_run_id': 'sbx_test'}}}}}

    def test_ac_8_3_scan_decodes_nested_strings_and_rejects_account_suffix(self):
        for prefix in ('sk_', 'rk_', 'whsec_', '_secret_', '@', 'acct_', 'acct_' + 'REDACTEDx'):
            with self.subTest(prefix=prefix), self.assertRaises(Failure):
                f.scan({'nested': [prefix + 'private']})
        f.scan({'account': 'acct_' + 'REDACTED'})
        with self.assertRaises(Failure):
            f.scan(json.loads('{"value":"\\u0073k_private"}'))

    def test_ac_8_3_select_requires_current_run_and_charge(self):
        row = self.webhook()
        self.assertIs(f.select_s1([row], 'sbx_test', {'ch_synthetic'}), row)
        for run, charges in [('sbx_other', {'ch_synthetic'}), ('sbx_test', {'ch_other'})]:
            with self.assertRaises(Failure):
                f.select_s1([row], run, charges)
        row['body']['livemode'] = True
        with self.assertRaises(Failure):
            f.select_s1([row], 'sbx_test', {'ch_synthetic'})

    def test_ac_8_3_export_is_mirrored_and_rescan_precedes_writes(self):
        row = self.webhook()
        item = {'id': 'txn_synthetic', 'type': 'charge', 'source': 'ch_synthetic', 'amount': 1234,
                'fee': 66, 'net': 1168, 'currency': 'cad', 'created': 1793617200}
        api = {'method': 'GET', 'path': '/v1/balance_transactions', 'status': 200,
               'recorded_at': row['recorded_at'], 'body': {'data': [item], 'has_more': False}}
        with tempfile.TemporaryDirectory() as temporary:
            dest, mirror = Path(temporary) / 'main', Path(temporary) / 'sim'
            manifest = f.export_s1({'run_id': 'sbx_test'}, row, [api], dest, mirror)
            self.assertEqual(len(manifest['files']), 2)
            self.assertEqual(manifest['files'][1]['expected']['items'][0]['fee'], 66)
            original = {x.name: x.read_bytes() for x in dest.iterdir()}
            self.assertEqual(original, {x.name: x.read_bytes() for x in mirror.iterdir()})
            row['body']['unknown'] = 'sk_' + 'private'
            with self.assertRaises(Failure):
                f.export_s1({'run_id': 'sbx_test'}, row, [], dest, mirror)
            self.assertEqual(original, {x.name: x.read_bytes() for x in dest.iterdir()})
            del row['body']['unknown']
            manifest = f.export_s1({'run_id': 'sbx_test'}, row, [], dest, mirror)
            self.assertEqual(manifest['api_recording'], 'not_available')
            self.assertFalse((dest / '02-api-balance_transactions.json').exists())

    def test_ac_8_3_recording_pages_use_monotonic_cursor(self):
        first = [{'id': n} for n in range(1, 101)]
        with patch.object(f, 'get_json', side_effect=[first, [{'id': 101}]]) as get:
            self.assertEqual(len(list(f.recordings('webhook'))), 101)
            self.assertIn('after_id=100', get.call_args.args[1])
        with patch.object(f, 'get_json', return_value=[{'id': 1}, {'id': 1}]):
            with self.assertRaises(Failure):
                list(f.recordings('webhook'))


class PayoutTests(unittest.TestCase):
    START = '2026-11-02T00:00:00Z'

    def payout(self, identity='po_one', **updates):
        return dict(id=identity, created=1793577600, livemode=False, automatic=True,
                    status='paid', reconciliation_status='completed', **updates)

    def run_pages(self, pages):
        class Stripe:
            def request(self, path):
                self.paths.append(path)
                return next(self.pages)
        stripe = Stripe()
        stripe.pages, stripe.paths = iter(pages), []
        result = p.check_payout(stripe, self.START)
        return result, stripe.paths

    def test_ac_8_8_reads_all_pages_even_after_ready_payout(self):
        result, paths = self.run_pages([
            {'data': [self.payout()], 'has_more': True},
            {'data': [self.payout('po_two')], 'has_more': False}])
        self.assertEqual(result[0], 0)
        self.assertEqual(result[1]['payouts'], ['po_one', 'po_two'])
        self.assertIn('starting_after=po_one', paths[1])
        self.assertIn('created%5Bgte%5D=1793577600', paths[0])

    def test_ac_8_8_empty_pending_manual_all_wait(self):
        pending, manual = self.payout(), self.payout()
        pending['reconciliation_status'] = 'in_progress'
        manual['automatic'] = False
        for items in ([], [pending], [manual]):
            result, _ = self.run_pages([{'data': items, 'has_more': False}])
            self.assertEqual(result[0], 75)

    def test_ac_8_8_bad_pages_live_and_repeated_cursor_fail(self):
        live = self.payout()
        live['livemode'] = True
        for pages in ([{'data': [], 'has_more': True}], [{'data': [live], 'has_more': False}],
                      [{'data': [], 'has_more': 'false'}],
                      [{'data': [self.payout()], 'has_more': True}] * 2):
            with self.assertRaises(Failure):
                self.run_pages(pages)

    def test_ac_8_8_query_failure_exits_two(self):
        with patch.object(p, 'load_run', side_effect=Failure('offline')), patch.object(p, 'emit'):
            self.assertEqual(p.main(), 2)


if __name__ == '__main__':
    unittest.main()
