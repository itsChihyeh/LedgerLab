"""AC-8.5 offline page completeness, replay, bank and acceptance assertions."""
import json
import tempfile
import unittest
from datetime import date
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import catchup
import duplicates
from runtime import Failure


def event(number):
    return {'id': 'evt_test' + str(number), 'created': 10, 'type': 'charge.succeeded', 'livemode': False}


def payment():
    return {'id': 'pay_test', 'processorPaymentRef': 'ch_test', 'status': 'CAPTURED', 'amount': 500,
            'postings': [{'entryId': 'entry_test', 'entryType': 'PAYMENT_CAPTURED', 'side': side, 'amount': 500}
                         for side in ('D', 'C')]}


class CatchupTest(unittest.TestCase):
    def test_ac_8_5_merges_all_pages_and_deduplicates_overlap(self):
        pages = iter([{'data': [event(1), event(2)], 'has_more': True},
                      {'data': [event(2), event(3)], 'has_more': False}])
        cursors = []
        def fetch(cursor):
            cursors.append(cursor)
            return next(pages)
        rows, count = catchup.collect_events(fetch, 0, 20)
        self.assertEqual(2, count)
        self.assertEqual([None, 'evt_test2'], cursors)
        self.assertEqual([event(i)['id'] for i in (1, 2, 3)], [r['id'] for r in rows])

    def test_ac_8_5_failed_second_page_exposes_no_partial_result(self):
        calls = []
        def fetch(cursor):
            calls.append(cursor)
            if cursor:
                raise Failure('HTTP_502')
            return {'data': [event(1)], 'has_more': True}
        with self.assertRaises(Failure):
            catchup.collect_events(fetch, 0, 20)
        self.assertEqual(2, len(calls))

    def test_ac_8_5_malformed_live_outside_window_and_cursor_loops_fail(self):
        for page in ({'data': [], 'has_more': True}, {'data': [], 'has_more': 'false'},
                     {'data': [dict(event(1), livemode=True)], 'has_more': False},
                     {'data': [dict(event(1), created=20)], 'has_more': False},
                     {'data': [dict(event(1), type='unsubscribed')], 'has_more': False},
                     {'data': [event(1)], 'has_more': True}):
            with self.subTest(page=page), self.assertRaises(Failure):
                catchup.collect_events(lambda _: page, 0, 20)

    def test_ac_8_5_only_404_means_missing_not_unprocessed_or_http_errors(self):
        events = [event(1), event(2)]
        result = catchup.missing_events(events, lambda ref: None if ref.endswith('1')
                                       else {'eventId': ref, 'status': 'REJECTED'})
        self.assertEqual([events[0]], result)
        with self.assertRaises(Failure):
            catchup.missing_events(events, lambda _: {'eventId': 'evt_wrong'})
        with self.assertRaises(Failure):
            catchup.missing_events(events, lambda _: (_ for _ in ()).throw(Failure('HTTP_500')))

    def test_ac_8_5_incomplete_listing_never_resends_or_changes_missing_file_or_bank(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(catchup, 'OUT', Path(directory)), patch.object(catchup, 'ROOT', Path(directory)), \
                patch.object(catchup, 'load_run', return_value={'run_id': 'sbx_test', 'window_start': 'start'}), \
                patch.object(catchup, 'list_events', side_effect=Failure('HTTP_502')), \
                patch.object(catchup, 'resend') as replay, patch.object(catchup, 'sync_bank') as bank, \
                patch.object(catchup, 'STATE', Path(directory) / 'state'):
            self.assertEqual(2, catchup.catchup())
            replay.assert_not_called()
            bank.assert_not_called()
            self.assertFalse((Path(directory) / 'state/missing-events.json').exists())
            report = json.loads(next(Path(directory).glob('catchup-*.json')).read_text(encoding='utf-8'))
            self.assertFalse(report['complete'])
            self.assertIn('不完整', report['reason'])

    def test_ac_8_5_resend_checks_json_not_only_exit_status(self):
        for value, code in (({'error': {'code': 'resource_missing'}}, 0),
                            ({'id': 'evt_test1', 'livemode': True}, 0),
                            ({'id': 'evt_test1', 'livemode': False}, 1)):
            with patch.object(catchup, 'cli', return_value=SimpleNamespace(returncode=code, stdout=json.dumps(value))):
                with self.assertRaises(Failure):
                    catchup.resend('evt_test1')
        with patch.object(catchup, 'cli', return_value=SimpleNamespace(
                returncode=0, stdout=json.dumps({'id': 'evt_test1', 'livemode': False}))) as cli:
            catchup.resend('evt_test1')
            cli.assert_called_once_with(['events', 'resend', 'evt_test1'])

    def test_ac_8_5_missing_wait_is_bounded_and_rechecks(self):
        now = [0]
        def sleep(seconds):
            now[0] += seconds
        with patch.object(catchup, 'lookup_event', return_value=None) as lookup:
            self.assertEqual([event(1)], catchup.await_missing([event(1)], seconds=5,
                             monotonic=lambda: now[0], sleep=sleep))
            self.assertEqual(5, now[0])
            self.assertEqual(4, lookup.call_count)

    def test_ac_8_5_bank_dates_include_pending_visibility_and_today(self):
        self.assertEqual(['2026-11-01', '2026-11-02', '2026-11-03'], catchup.bank_dates(
            [{'visible_date': '2026-11-01', 'booking_date': '2026-11-02'}], date(2026, 11, 3)))
        self.assertEqual(['2026-11-03'], catchup.bank_dates([], date(2026, 11, 3)))

    def test_ac_8_5_csv_is_imported_byte_for_byte_and_rejected_files_fail(self):
        csv = b'H,BANK_STATEMENT\r\nT,0\r\n'
        for status in ('ACCEPTED', 'REJECTED'):
            with patch.object(catchup, 'get_json', side_effect=[
                    {'seen': 0, 'received': 0, 'pages': 1, 'httpStatus': 200, 'errorCode': ''},
                    {'now': '2026-11-03T12:00:00Z'}, []]), \
                    patch.object(catchup, 'simulator_sql', return_value='2026-11-03'), \
                    patch.object(catchup, 'request', side_effect=[(200, csv),
                        (200, json.dumps({'fileId': 'file_test', 'status': status}).encode())]) as http:
                if status == 'ACCEPTED':
                    catchup.sync_bank({}, {})
                else:
                    with self.assertRaises(Failure):
                        catchup.sync_bank({}, {})
                self.assertEqual(csv, http.call_args_list[1].args[3])
                self.assertEqual('text/csv', http.call_args_list[1].args[4]['Content-Type'])

    def test_ac_8_5_one_capture_means_one_entry_not_two_posting_rows(self):
        with patch.object(duplicates, 'get_json', return_value=payment()):
            self.assertEqual('entry_test', duplicates.payment_evidence('ch_test')['capture_entry_id'])
        broken = payment()
        broken['postings'][1]['entryId'] = 'entry_second'
        with patch.object(duplicates, 'get_json', return_value=broken), self.assertRaises(Failure):
            duplicates.payment_evidence('ch_test')

    def test_ac_8_5_inbox_subject_is_processor_charge_not_local_payment_id(self):
        duplicates.assert_payment_link({'subject_id': 'ch_test'}, {'charge': 'ch_test', 'payment_id': 'pay_test'})
        with self.assertRaises(Failure):
            duplicates.assert_payment_link({'subject_id': 'ch_other'}, {'charge': 'ch_test', 'payment_id': 'pay_test'})

    def test_ac_8_5_duplicate_deliveries_preserve_single_business_result(self):
        inbox = {'eventId': 'evt_test1', 'status': 'PROCESSED', 'result': 'APPLIED', 'livemode': False,
                 'subjectId': 'pay_test', 'deliveries': [{'id': 1, 'outcome': 'NEW'},
                                                      {'id': 2, 'outcome': 'DUPLICATE'}]}
        with patch.object(duplicates, 'get_json', return_value=inbox):
            self.assertEqual(2, duplicates.inbox_evidence('evt_test1', 2)['deliveries'])
            with self.assertRaises(Failure):
                duplicates.inbox_evidence('evt_test1', 3)
        inbox['deliveries'][1]['outcome'] = 'NEW'
        with patch.object(duplicates, 'get_json', return_value=inbox), self.assertRaises(Failure):
            duplicates.inbox_evidence('evt_test1', 2)

    def test_ac_8_5_reorder_requires_two_complete_distinct_events_before_release(self):
        def row(i, event_id, receipt, action, attempt):
            return {'id': i, 'event_id': event_id, 'receipt_id': receipt, 'action': action,
                    'attempt_no': attempt, 'http_status': 200}
        rows = [row(1, 'evt_first', 'a', 'HOLD_AND_REORDER', 0),
                row(2, 'evt_second', 'b', 'DUPLICATE', 3), row(3, 'evt_third', 'c', 'DUPLICATE', 3),
                row(4, 'evt_first', 'a', 'HOLD_AND_REORDER', 1)]
        self.assertEqual(2, duplicates.assert_reorder(rows, 'evt_first', {'evt_second', 'evt_third'})['overtaking_events'])
        for wrong in (rows[:2] + rows[3:], [dict(r, id=5) if r['id'] == 3 else r for r in rows]):
            with self.assertRaises(Failure):
                duplicates.assert_reorder(wrong, 'evt_first', {'evt_second', 'evt_third'})

    def test_ac_8_5_each_of_three_copies_must_be_accepted_even_on_rerun(self):
        rows = [{'event_id': 'evt_test', 'receipt_id': 'a', 'action': 'DUPLICATE',
                 'attempt_no': i, 'http_status': 200} for i in range(4)]
        duplicates.assert_copies(rows, {'evt_test'})
        for wrong in (rows[:-1], rows + [rows[0]],
                      [dict(r, http_status=401) if r['attempt_no'] == 1 else r for r in rows]):
            with self.assertRaises(Failure):
                duplicates.assert_copies(wrong, {'evt_test'})

    def test_ac_8_5_traffic_failure_still_restores_plan_and_writes_failure(self):
        old = [{'card': 'pm_card_bypassPending', 'seed': 1, 'ordinal': i, 'charge': 'ch_test' + str(i)}
               for i in range(3)]
        def http(base, path, *args, **kwargs):
            if path == '/sim/relay/plan':
                return {'rules': []}
            if path.startswith('/api/payments/'):
                return {'status': 'CAPTURED'}
            if path.startswith('/api/events/'):
                return {'status': 'PROCESSED', 'deliveries': [{}, {}, {}]}
            if path == '/sim/clock':
                return {'now': '2026-11-03T00:00:00Z'}
            if path.startswith('/sim/relay/stats?'):
                return {'inbound': {}}
            self.fail(path)
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(duplicates, 'OUT', Path(directory)), patch.object(duplicates, 'ROOT', Path(directory)), \
                patch.object(duplicates, 'load_run', return_value={'run_id': 'sbx_test'}), \
                patch.object(duplicates, 'current_payments', return_value=old), \
                patch.object(duplicates, 'get_json', side_effect=http), patch.object(duplicates, 'audit', return_value={}), \
                patch.object(duplicates, 'find_events', return_value={r['charge']: 'evt_test' + str(i)
                                                                   for i, r in enumerate(old)}), \
                patch.object(duplicates, 'payment_evidence', return_value={}), \
                patch.object(duplicates, 'inbox_evidence', return_value={'deliveries': 1}), \
                patch.object(duplicates, 'resend'), patch.object(duplicates, 'simulator_sql', return_value=0), \
                patch.object(duplicates, 'traffic', side_effect=Failure('TRAFFIC_FAILED')), \
                patch.object(duplicates, 'set_plan') as plan:
            self.assertEqual(1, duplicates.accept_duplicates())
            self.assertEqual([], plan.call_args.args[0])
            report = json.loads(next(Path(directory).glob('accept-*.json')).read_text())
            self.assertTrue(report['plan_restored'])
            self.assertEqual('TRAFFIC_FAILED', report['reason'])


if __name__ == '__main__':
    unittest.main()
