"""Self-check of the pure helpers in demo.py (no Docker): python scripts/demo/selftest.py"""
import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import demo  # noqa: E402

NOW = datetime(2026, 11, 5, 3, 0, 7, tzinfo=timezone.utc)


class DemoHelpers(unittest.TestCase):
    def test_secrets_are_distinct_and_cover_every_role(self):
        values = demo.new_secrets()
        self.assertEqual(set(values), set(demo.PASSWORDS))
        self.assertEqual(len(set(values.values())), len(demo.PASSWORDS))

    def test_env_round_trip(self):
        values = {'A': 'x-y_z', 'B': '123'}
        self.assertEqual(demo.parse_env(demo.format_env(values)), values)
        self.assertEqual(demo.parse_env('# c\n\nK="v"\r\nJ=\'w\'\n'), {'K': 'v', 'J': 'w'})

    def test_clock_start_is_in_the_past(self):
        self.assertEqual(demo.sim_clock_start(NOW), '2026-10-22T03:00:07Z')

    def test_archive_names_never_overwrite_the_first_of_a_day(self):
        self.assertEqual(demo.archive_filename(NOW, set()), '2026-11-05.json')
        self.assertEqual(demo.archive_filename(NOW, {'2026-11-05.json'}), '2026-11-05T030007Z.json')

    def test_prune_keeps_the_newest_seven(self):
        names = ['2026-11-%02d.json' % day for day in range(1, 11)] + ['last-reset.txt', 'notes.json']
        self.assertEqual(demo.prune(names), ['2026-11-01.json', '2026-11-02.json', '2026-11-03.json'])
        self.assertEqual(demo.prune(names[:3]), [])

    def test_verdict_and_record(self):
        audit = {'checkedAt': 'T', 'checks': [{'id': 'INV-1', 'ok': True}, {'id': 'INV-7', 'ok': False}]}
        result = demo.verdict(audit)
        self.assertFalse(result['ok'])
        self.assertEqual(result['failed'], ['INV-7'])
        self.assertFalse(demo.verdict({'checks': []})['ok'])
        record = demo.archive_record({'day': '2026-11-05', 'invariants': {'stale': True}}, audit, NOW)
        self.assertEqual(record['archivedAt'], '2026-11-05T03:00:07Z')
        self.assertEqual(record['invariants']['failed'], ['INV-7'])

    def test_all_zero(self):
        self.assertTrue(demo.all_zero({'pay': 0, 'x': 0}))
        self.assertFalse(demo.all_zero({'pay': 1}))

    def test_the_stack_never_names_another_project(self):
        self.assertEqual(demo.PROJECT, 'ledgerlab-demo')
        text = (demo.ROOT / 'compose.demo.yaml').read_text(encoding='utf-8')
        self.assertIn('name: ledgerlab-demo', text)
        for port in ('127.0.0.1:28080:8080', '127.0.0.1:28081:8081', '127.0.0.1:25432:5432'):
            self.assertIn(port, text)
        self.assertNotIn('sandbox', text)


if __name__ == '__main__':
    unittest.main()
