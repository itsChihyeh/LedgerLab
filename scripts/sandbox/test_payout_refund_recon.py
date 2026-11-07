"""AC-8.6/8.8/8.9 offline checks: pagination, line comparison, batch verdicts, refund choice, findings."""
import unittest
from datetime import date

import payout_refund_recon as t
from runtime import Failure


def txn(n, kind='charge', amount=1000, fee=59, net=941):
    return {'id': 'txn_t' + str(n), 'type': kind, 'amount': amount, 'fee': fee, 'net': net}


def line(n, kind='PAYMENT', gross=1000, fee=59, net=941):
    return {'balanceTxnId': 'txn_t' + str(n), 'lineType': kind, 'gross': gross, 'fee': fee, 'net': net}


def detail(status='CONFIRMED', **batch):
    base = {'batchId': 'po_t', 'status': status, 'payoutAmount': 941, 'net': 941, 'holdReason': None}
    base.update(batch)
    return {'batch': base, 'lines': [line(1)], 'history': [],
            'entries': [{'entryType': 'BATCH_SETTLED'}]}


class PayoutTest(unittest.TestCase):
    def test_ac_8_8_payout_row_is_skipped_and_lines_compare_equal(self):
        rows = [txn(1), txn(2, 'refund', -100, 0, -100), txn(9, 'payout', -841, 0, -841)]
        local = t.local_lines([line(1), line(2, 'REFUND', -100, 0, -100)])
        self.assertEqual([], t.line_differences(t.stripe_lines(rows), local))

    def test_ac_8_8_missing_extra_and_different_lines_are_reported(self):
        stripe = t.stripe_lines([txn(1), txn(2), txn(3, amount=5)])
        local = t.local_lines([line(1), line(3), line(4)])
        self.assertEqual({('txn_t2', 'MISSING_LOCALLY'), ('txn_t3', 'DIFFERENT'), ('txn_t4', 'NOT_IN_STRIPE')},
                         {(d['txn'], d['problem']) for d in t.line_differences(stripe, local)})

    def test_ac_8_8_unknown_stripe_type_never_matches(self):
        stripe = t.stripe_lines([txn(1, 'adjustment')])
        self.assertEqual('DIFFERENT', t.line_differences(stripe, t.local_lines([line(1)]))[0]['problem'])

    def test_ac_8_8_confirmed_waits_for_bank_banked_passes(self):
        payout = {'amount': 941}
        self.assertEqual('WAITING_BANK', t.judge_batch(detail(), payout)[0])
        self.assertEqual('PASS', t.judge_batch(detail('BANKED'), payout)[0])

    def test_ac_8_8_held_is_failure_with_reason_never_waiting(self):
        held = detail('HELD', holdReason='UNKNOWN_REFUND')
        held['history'] = [{'holdReason': 'UNKNOWN_REFUND', 'evidenceId': 're_x'}]
        verdict, facts = t.judge_batch(held, {'amount': 941})
        self.assertEqual('FAIL', verdict)
        self.assertEqual('UNKNOWN_REFUND', facts['hold_reason'])
        self.assertEqual('re_x', facts['reasons'][0]['evidence'])

    def test_ac_8_8_amount_or_settled_entry_mismatch_fails(self):
        self.assertEqual('FAIL', t.judge_batch(detail('BANKED'), {'amount': 940})[0])
        none = detail('BANKED')
        none['entries'] = []
        self.assertEqual('FAIL', t.judge_batch(none, {'amount': 941})[0])


class PagesTest(unittest.TestCase):
    def page(self, ids, more):
        return {'data': [{'id': 'txn_' + i} for i in ids], 'has_more': more}

    def test_ac_8_8_all_pages_are_followed_with_cursor(self):
        pages, cursors = iter([self.page('ab', True), self.page('c', False)]), []
        def fetch(cursor):
            cursors.append(cursor)
            return next(pages)
        self.assertEqual(['txn_a', 'txn_b', 'txn_c'], [i['id'] for i in t.collect_pages(fetch, 'txn')])
        self.assertEqual([None, 'txn_b'], cursors)

    def test_ac_8_8_cursor_loop_and_bad_ids_fail_closed(self):
        with self.assertRaises(Failure):
            t.collect_pages(lambda c: self.page('a', True), 'txn')
        with self.assertRaises(Failure):
            t.collect_pages(lambda c: {'data': [{'id': 'ch_x'}], 'has_more': False}, 'txn')


class RefundChoiceTest(unittest.TestCase):
    def test_ac_8_6_fresh_run_takes_two_distinct_payments(self):
        self.assertEqual({'a': 'ch_1', 'b': 'ch_2'}, t.pick_payments(['ch_1', 'ch_2', 'ch_3'], {}))

    def test_ac_8_6_rerun_reuses_payments_already_refunded_by_this_script(self):
        refunded = {'ch_2': 't07-ac86-a', 'ch_3': 't07-ac86-b', 'ch_1': 'someone-else'}
        self.assertEqual({'a': 'ch_2', 'b': 'ch_3'}, t.pick_payments(['ch_1', 'ch_2', 'ch_3'], refunded))

    def test_ac_8_6_fewer_than_two_usable_payments_fails(self):
        with self.assertRaises(Failure):
            t.pick_payments(['ch_1'], {})


class ReconTest(unittest.TestCase):
    def run_of(self, kind, *subjects):
        return {'kind': kind, 'findings': [{'fingerprint': 'fp' + s, 'finding': {
            'category': 'MISSING', 'subjectType': 'x', 'subjectId': s, 'expected': '', 'actual': '',
            'evidenceRefs': ''}} for s in subjects]}

    def test_ac_8_9_stripe_subjects_and_traced_ids(self):
        rows = t.classify_findings([self.run_of('PROCESSOR', 'po_abc', 'bank_1')])
        self.assertTrue(rows[0]['stripe_subject'])
        self.assertFalse(rows[1]['stripe_subject'])
        self.assertEqual([], rows[1]['traced_ids'])

    def test_ac_8_9_non_stripe_subject_traces_through_evidence(self):
        run = self.run_of('BANK', 'bank_1')
        run['findings'][0]['finding']['evidenceRefs'] = 'deposit for po_xyz'
        self.assertEqual(['po_xyz'], t.classify_findings([run])[0]['traced_ids'])

    def test_ac_8_9_comparable_ignores_order_and_run_ids(self):
        a = {'inputHashes': {'f1': 'h1', 'f2': 'h2'}, 'findings': [{'fingerprint': 'b'}, {'fingerprint': 'a'}],
             'cutoffAt': 'c', 'evidenceAsOf': 'e', 'id': 'run_1'}
        b = dict(a, id=None, inputHashes={'f2': 'h2', 'f1': 'h1'}, findings=[{'fingerprint': 'a'}, {'fingerprint': 'b'}])
        self.assertEqual(t.comparable(a), t.comparable(b))
        self.assertNotEqual(t.comparable(a), t.comparable(dict(b, cutoffAt='other')))

    def test_ac_8_9_business_dates_cover_window_through_today(self):
        self.assertEqual(['2026-10-31', '2026-11-01'], t.business_dates(date(2026, 10, 31), date(2026, 11, 1)))
        self.assertEqual(['2026-11-01'], t.business_dates(date(2026, 11, 1), date(2026, 11, 1)))


if __name__ == '__main__':
    unittest.main()
