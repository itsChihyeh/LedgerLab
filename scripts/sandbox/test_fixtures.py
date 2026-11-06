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


def refund(status='succeeded', identity='re_one'):
    return {'id': identity, 'object': 'refund', 'amount': 100, 'charge': 'ch_one', 'currency': 'cad',
            'status': status, 'metadata': {'ledgerlab_refund_id': 'rfd_one'}}


def api(method, path, body, number):
    return {'id': number, 'method': method, 'path': path, 'status': 200, 'body': body,
            'recorded_at': '2026-11-06T00:00:00Z'}


def refund_event(kind, identity='re_one'):
    return {'recorded_at': '2026-11-06T00:00:01Z', 'body': {
        'id': 'evt_' + kind, 'type': kind, 'api_version': f.VERSION, 'created': 1793932753,
        'livemode': False, 'request': {'id': 'req_one', 'idempotency_key': 'rfd_one'},
        'data': {'object': refund(identity=identity)}}}


class RefundAndPayoutFixtureTests(unittest.TestCase):
    def rows(self, status='succeeded'):
        return [api('POST', '/v1/refunds', refund(status), 1),
                api('GET', '/v1/refunds', {'data': [refund(status)], 'has_more': False}, 2),
                api('GET', '/v1/refunds/re_one', refund(status), 3)]

    def hooks(self):
        return [refund_event('refund.created'), refund_event('refund.updated')]

    def test_ac_8_3_s2_needs_current_run_charge_and_both_events(self):
        bundle = f.select_refund(self.rows(), self.hooks(), {'ch_one'}, 'succeeded')
        self.assertEqual(bundle['post']['id'], 1)
        self.assertIsNotNone(bundle['retrieve'])
        self.assertIsNone(f.select_refund(self.rows(), self.hooks(), {'ch_other'}, 'succeeded'))
        self.assertIsNone(f.select_refund(self.rows(), self.hooks()[:1], {'ch_one'}, 'succeeded'))
        incomplete = self.rows()
        incomplete[1]['body']['has_more'] = True
        self.assertIsNone(f.select_refund(incomplete, self.hooks(), {'ch_one'}, 'succeeded'))

    def test_ac_8_3_s3_is_not_reproduced_when_no_refund_was_pending(self):
        self.assertIsNone(f.select_refund(self.rows(), self.hooks(), {'ch_one'}, 'pending'))
        self.assertIsNotNone(f.select_refund(self.rows('pending'), self.hooks(), {'ch_one'}, 'pending'))

    def test_ac_8_3_refund_export_is_mirrored_and_rejects_secrets(self):
        bundle = f.select_refund(self.rows(), self.hooks(), {'ch_one'}, 'succeeded')
        with tempfile.TemporaryDirectory() as temporary:
            dest, mirror = Path(temporary) / 'main', Path(temporary) / 'sim'
            manifest = f.export_refund('s2', {'run_id': 'sbx_test'}, bundle, dest, mirror)
            self.assertEqual([x['kind'] for x in manifest['files']], ['api', 'webhook', 'webhook', 'api', 'api'])
            self.assertEqual(manifest['files'][0]['expected']['refund']['refundId'], 'rfd_one')
            self.assertEqual({x.name: x.read_bytes() for x in dest.iterdir()},
                             {x.name: x.read_bytes() for x in mirror.iterdir()})
            self.assertNotIn(b'\r\n', (dest / 'manifest.json').read_bytes())
            created = json.loads((dest / '02-webhook-refund.created.json').read_text(encoding='utf-8'))
            self.assertEqual('IDEMPOTENCY_KEY_REDACTED', created['request']['idempotency_key'])
            self.assertEqual('rfd_one', bundle['created']['body']['request']['idempotency_key'])
            bundle['post']['body']['unknown'] = 'whsec_' + 'private'
            with self.assertRaises(Failure):
                f.export_refund('s2', {'run_id': 'sbx_test'}, bundle, dest, mirror)

    def payout(self, status):
        return {'id': 'po_one', 'object': 'payout', 'amount': 500, 'currency': 'cad', 'automatic': True,
                'method': 'standard', 'status': status, 'reconciliation_status': 'completed',
                'arrival_date': 1793923200, 'created': 1793926049, 'livemode': False}

    def payout_hook(self, kind, status):
        return {'recorded_at': '2026-11-06T00:47:30Z', 'body': {
            'id': 'evt_' + kind, 'type': kind, 'api_version': f.VERSION, 'created': 1793926050,
            'livemode': False, 'data': {'object': self.payout(status)}}}

    def test_ac_8_3_s4_requires_full_lifecycle_retrieve_and_complete_lines(self):
        hooks = [self.payout_hook('payout.created', 'in_transit'),
                 self.payout_hook('payout.reconciliation_completed', 'in_transit'),
                 self.payout_hook('payout.paid', 'paid')]
        line = {'id': 'txn_p', 'type': 'payout', 'source': 'po_one', 'amount': -500, 'fee': 0, 'net': -500,
                'currency': 'cad', 'created': 1793926049}
        rows = [api('GET', '/v1/payouts/po_one', self.payout('in_transit'), 1),
                api('GET', '/v1/balance_transactions', {'data': [line], 'has_more': False}, 2)]
        bundle = f.select_s4(rows, hooks)
        self.assertEqual(bundle['paid']['body']['type'], 'payout.paid')
        self.assertIsNone(f.select_s4(rows, hooks[:2]))
        self.assertIsNone(f.select_s4(rows[:1], hooks))
        with tempfile.TemporaryDirectory() as temporary:
            dest, mirror = Path(temporary) / 'main', Path(temporary) / 'sim'
            manifest = f.export_s4({'run_id': 'sbx_test'}, bundle, dest, mirror)
            expected = manifest['files'][4]['expected']
            self.assertEqual(expected['pages'], 1)
            self.assertEqual(expected['items'][0]['net'], -500)
            self.assertEqual(manifest['files'][0]['expected']['payout']['arrivalDate'], '2026-11-06T00:00:00Z')


class PayoutTests(unittest.TestCase):
    START ='2026-11-02T00:00:00Z'

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
