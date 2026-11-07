"""Offline lifecycle, parsing, privacy and acceptance regressions (no Stripe or Docker)."""
import hashlib
import hmac
import json
import os
import tempfile
from types import SimpleNamespace
import unittest
from pathlib import Path
from unittest.mock import patch

import accept
import runtime
import stack


def row(number=1, **changes):
    return dict({'id': number * 2, 'run_id': 'sbx_test', 'card': 'pm_card_bypassPending',
                 'seed': 1, 'ordinal': number, 'charge': 'ch_test' + str(number),
                 'status': 'succeeded', 'error_code': ''}, **changes)


class SandboxTest(unittest.TestCase):
    def test_ac_8_4_database_passwords_are_generated_once_and_preserved_with_signing_secret(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(runtime, 'STATE', Path(directory)):
            runtime.ensure_database_passwords()
            first = runtime.read_secrets()
            self.assertEqual(set(runtime.PASSWORDS), set(first))
            self.assertEqual(5, len(set(first.values())))
            self.assertTrue(all(len(value) >= 32 for value in first.values()))
            runtime.update_secrets({'STRIPE_WEBHOOK_SECRET': 'whsec_' + 'offline'})
            runtime.ensure_database_passwords()
            self.assertEqual(first, {key: runtime.read_secrets()[key] for key in runtime.PASSWORDS})
            self.assertEqual('whsec_' + 'offline', runtime.load_secret())
            self.assertNotIn(first['POSTGRES_PASSWORD'], runtime.safe(first['POSTGRES_PASSWORD']))

    def test_ac_8_4_database_credentials_override_inherited_environment_and_purge_removes_only_passwords(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(runtime, 'STATE', Path(directory)):
            runtime.ensure_database_passwords()
            runtime.update_secrets({'STRIPE_WEBHOOK_SECRET': 'whsec_' + 'offline'})
            expected = runtime.read_secrets()['POSTGRES_PASSWORD']
            with patch.dict(os.environ, {'POSTGRES_PASSWORD': 'wrong-inherited-password',
                                         'STRIPE_API_KEY': 'rk_' + 'test_offline'}):
                env = runtime.compose_env({'run_id': 'sbx_test', 'window_start': '2026-10-20T00:00:00Z'})
            self.assertEqual(expected, env['POSTGRES_PASSWORD'])
            runtime.purge_database_passwords()
            self.assertEqual({'STRIPE_WEBHOOK_SECRET': 'whsec_' + 'offline'}, runtime.read_secrets())
            runtime.ensure_database_passwords()
            self.assertNotEqual(expected, runtime.read_secrets()['POSTGRES_PASSWORD'])

    def test_ac_8_4_invalid_or_duplicate_saved_password_is_rejected_without_rotation(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(runtime, 'STATE', Path(directory)):
            path = Path(directory) / 'secrets.env'
            for content in ('POSTGRES_PASSWORD=\n', 'POSTGRES_PASSWORD=a\nPOSTGRES_PASSWORD=b\n'):
                path.write_text(content)
                with self.assertRaises(runtime.Failure):
                    runtime.ensure_database_passwords()
                self.assertEqual(content, path.read_text())

    def test_ac_8_4_cli_signing_secret_refresh_preserves_database_credentials(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(runtime, 'STATE', Path(directory)), \
                patch.object(stack, 'stop_listener') as stop, \
                patch.object(stack, 'cli', return_value=SimpleNamespace(
                    returncode=0, stdout='whsec_' + 'offline\n')):
            runtime.ensure_database_passwords()
            first = runtime.read_secrets()
            stack.save_secret()
            stack.save_secret()
            stop.assert_called_once()
            self.assertEqual(first, {key: runtime.read_secrets()[key] for key in runtime.PASSWORDS})

    def test_ac_8_4_only_successful_confirmed_purge_removes_passwords(self):
        for purge, succeeds in ((False, True), (True, False), (True, True)):
            with tempfile.TemporaryDirectory() as directory, \
                    patch.object(runtime, 'STATE', Path(directory)), patch.object(stack, 'STATE', Path(directory)), \
                    patch.object(stack, 'stop_listener'), patch('builtins.input', return_value='sbx_test'), \
                    patch.object(stack, 'compose', side_effect=None if succeeds else runtime.Failure('COMPOSE_FAILED')):
                runtime.ensure_database_passwords()
                runtime.write_json(Path(directory) / 'run.json',
                                   {'run_id': 'sbx_test', 'window_start': '2026-10-20T00:00:00Z'})
                if succeeds:
                    stack.down(SimpleNamespace(purge=purge))
                else:
                    with self.assertRaises(runtime.Failure):
                        stack.down(SimpleNamespace(purge=purge))
                self.assertEqual(not (purge and succeeds), 'POSTGRES_PASSWORD' in runtime.read_secrets())

    def test_ac_8_4_cli_zero_exit_error_is_not_account_success(self):
        value = runtime.cli_json('{"error":{"code":"resource_missing"}}')
        with self.assertRaises(runtime.Failure) as caught:
            runtime.verify_account(value)
        self.assertEqual(78, caught.exception.exit_code)
        self.assertIn('另一个沙盒', str(caught.exception))

    def test_ac_8_4_cli_exact_test_event_required(self):
        runtime.verify_account({'id': runtime.PROBE_EVENT, 'livemode': False})
        for value in ({'id': runtime.PROBE_EVENT, 'livemode': True}, {'id': 'evt_other', 'livemode': False},
                      {'error': {'code': 'api_key_expired'}}, {}):
            with self.assertRaises(runtime.Failure) as caught:
                runtime.verify_account(value)
            self.assertEqual(78, caught.exception.exit_code)

    def test_ac_8_4_cli_invalid_json_is_not_success(self):
        for output in ('', 'Please login', '[]'):
            with self.assertRaises(runtime.Failure):
                runtime.cli_json(output)

    def test_ac_8_4_login_child_never_inherits_rest_key(self):
        with patch.dict(os.environ, {'STRIPE_CLI': 'stripe', 'STRIPE_API_KEY': 'rk_' + 'test_example',
                                     'STRIPE_CLI_USE_LOGIN': '1'}):
            args, env = runtime.cli_command(['events', 'retrieve', runtime.PROBE_EVENT])
        self.assertNotIn('STRIPE_API_KEY', env)
        self.assertNotIn('--api-key', args)

    def test_ac_8_4_run_reused_and_window_is_explicit(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(stack, 'STATE', Path(directory)):
            first = stack.select_run(False, None)
            self.assertEqual('2026-10-20T00:00:00Z', first['window_start'])
            self.assertEqual(first, stack.select_run(False, None))
            with self.assertRaises(runtime.Failure):
                stack.select_run(False, '2026-10-21T00:00:00Z')
            second = stack.select_run(True, '2026-10-21T00:00:00Z')
            self.assertNotEqual(first['run_id'], second['run_id'])
            self.assertTrue((Path(directory) / 'runs' / (first['run_id'] + '.json')).exists())

    def test_ac_8_4_window_requires_timezone(self):
        for window in ('nonsense', '2026-10-20T00:00:00'):
            with self.assertRaises(runtime.Failure):
                runtime.validate_run({'run_id': 'sbx_test', 'window_start': window})

    def test_ac_8_4_latest_attempts_do_not_duplicate_logical_payments(self):
        rows = [row(1), row(2), row(1, id=1, status='started', charge='')]
        latest = accept.latest_payments(rows, 'sbx_test')
        self.assertEqual(2, len(latest))
        self.assertEqual('succeeded', latest[0]['status'])
        with self.assertRaises(runtime.Failure):
            accept.latest_payments(rows, 'sbx_other')

    def test_ac_8_4_twenty_unique_successful_charges_required(self):
        rows = [row(i) for i in range(1, 21)]
        self.assertEqual(20, len(accept.successful_charges(rows)))
        for invalid in (rows[:19], rows[:19] + [row(20, status='failed')],
                        rows[:19] + [row(20, charge='ch_test1')],
                        rows[:19] + [row(20, error_code='UNKNOWN')]):
            with self.assertRaises(runtime.Failure):
                accept.successful_charges(invalid)

    def test_ac_8_4_signature_cases_change_exactly_one_byte_and_expire(self):
        body = b'{"id":"evt_test","livemode":false}'
        secret = 'whsec_' + 'offline'
        cases = accept.negative_cases(body, secret, 1000)
        expected = hmac.new(secret.encode(), b'1000.' + body, hashlib.sha256).hexdigest()
        self.assertEqual('t=1000,v1=' + expected, cases[0][2])
        self.assertEqual(1, sum(a != b for a, b in zip(body, cases[0][1])))
        self.assertIsNone(cases[1][2])
        self.assertEqual(body, cases[1][1])
        self.assertEqual(body, cases[2][1])
        self.assertTrue(cases[2][2].startswith('t=600,'))

    def test_ac_8_4_recording_hash_and_event_identity_are_verified(self):
        body = b'{"id":"evt_test","type":"charge.succeeded","livemode":false}'
        recorded = {'event_id': 'evt_test', 'body_hex': body.hex(), 'sha256': hashlib.sha256(body).hexdigest()}
        self.assertEqual(body, runtime.decode_recording(recorded)[0])
        for bad in (dict(recorded, sha256='0' * 64), dict(recorded, event_id='evt_wrong')):
            with self.assertRaises(runtime.Failure):
                runtime.decode_recording(bad)

    def test_ac_8_4_logs_redact_secrets_accounts_emails_and_database_passwords(self):
        values = ['rk_' + 'test_hidden', 'sk_' + 'live_hidden', 'whsec_' + 'hidden',
                  'acct_' + 'hidden', 'someone' + '@example.invalid', 'database-hidden']
        with patch.dict(os.environ, {'POSTGRES_PASSWORD': values[-1]}):
            result = runtime.safe(' '.join(values))
        for value in values:
            self.assertNotIn(value, result)

    def test_ac_8_4_polling_has_a_deadline_without_real_sleep(self):
        now = [0]
        def sleep(seconds):
            now[0] += seconds
        with self.assertRaises(runtime.Failure):
            runtime.wait_until(lambda: False, bool, 300, monotonic=lambda: now[0], sleep=sleep)
        self.assertEqual(300, now[0])

    def test_ac_8_4_capture_polling_does_not_accept_missing_inbox(self):
        now = [0]
        def sleep(seconds):
            now[0] += seconds
        report = {}
        with patch.object(accept, 'recordings', return_value=[]), patch.object(accept, 'get_json', return_value=None):
            with self.assertRaises(runtime.Failure):
                accept.wait_for_captured({}, ['ch_test'], report, seconds=3,
                                         monotonic=lambda: now[0], sleep=sleep)
        self.assertEqual(3, now[0])
        self.assertEqual(['ch_test'], report['missing_charges'])

    def test_ac_8_4_capture_requires_matching_recording_and_real_inbox(self):
        event = {'id': 'evt_test', 'type': 'charge.succeeded', 'livemode': False,
                 'api_version': accept.VERSION, 'data': {'object': {'id': 'ch_test'}}}
        body = json.dumps(event).encode()
        recording = {'event_id': event['id'], 'body_hex': body.hex(), 'sha256': hashlib.sha256(body).hexdigest()}
        payment = {'status': 'CAPTURED', 'processorPaymentRef': 'ch_test'}
        inbox = {'eventId': event['id'], 'eventType': event['type'], 'livemode': False,
                 'status': 'PROCESSED', 'apiVersion': accept.VERSION, 'deliveries': [{'outcome': 'NEW'}]}
        report = {}
        with patch.object(accept, 'recordings', return_value=[recording]), \
                patch.object(accept, 'get_json', side_effect=[payment, inbox]):
            self.assertEqual(body, accept.wait_for_captured({}, ['ch_test'], report))
        self.assertEqual(1, report['captured_count'])
        self.assertEqual([], report['missing_charges'])
        self.assertEqual('evt_test', report['payments'][0]['event_id'])

    def test_ac_8_4_settled_payment_counts_only_with_its_capture_entry(self):
        capture = {'entryType': 'PAYMENT_CAPTURED'}
        self.assertTrue(accept.captured_or_later({'status': 'CAPTURED'}))
        self.assertTrue(accept.captured_or_later({'status': 'SETTLED', 'postings': [capture]}))
        for payment in ({'status': 'SETTLED'}, {'status': 'SETTLED', 'postings': [{'entryType': 'BATCH_SETTLED'}]},
                        {'status': 'RECEIVED', 'postings': [capture]}):
            self.assertFalse(accept.captured_or_later(payment))

    def test_ac_8_4_accept_checks_three_negatives_and_audit_without_creating_payments(self):
        for violations, expected in (([], 0), ([{'invariant': 'INV-2', 'detail': 'bad balance'}], 1)):
            with tempfile.TemporaryDirectory() as directory, patch.object(accept, 'OUT', Path(directory)), \
                    patch.object(accept, 'ROOT', Path(directory)), \
                    patch.object(accept, 'load_run', return_value={'run_id': 'sbx_test'}), \
                    patch.object(accept, 'current_payments', return_value=[row(i) for i in range(1, 21)]), \
                    patch.object(accept, 'wait_for_captured', return_value=b'{"id":"evt_test"}'), \
                    patch.object(accept, 'load_secret', return_value='whsec_' + 'offline'), \
                    patch.object(accept, 'request', return_value=(401, b'')) as http, \
                    patch.object(accept, 'get_json', return_value={'checkedAt': '2026-11-02T00:00:00Z',
                                                                  'violations': violations}) as audit:
                self.assertEqual(expected, accept.accept())
                self.assertEqual(3, http.call_count)
                self.assertTrue(all(c.args[1] == '/webhooks/stripe' for c in http.call_args_list))
                audit.assert_called_once_with(accept.APP, '/api/audit/invariants')
                report = json.loads(next(Path(directory).glob('accept-*.json')).read_text())
                self.assertEqual('PASS' if expected == 0 else 'FAIL', report['result'])

    def test_ac_8_4_failures_still_write_safe_fail_report(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(accept, 'OUT', Path(directory)), \
                patch.object(accept, 'ROOT', Path(directory)), \
                patch.object(accept, 'load_run', side_effect=runtime.Failure('RUN_MISSING')):
            self.assertEqual(1, accept.accept())
            reports = list(Path(directory).glob('accept-*.json'))
            self.assertEqual(1, len(reports))
            self.assertEqual('FAIL', json.loads(reports[0].read_text())['result'])

    def test_ac_8_4_listener_pid_alone_does_not_prove_liveness(self):
        with patch.object(stack, 'read_json', return_value={'pid': 123, 'status': 'running', 'heartbeat': 1}):
            self.assertFalse(stack.listener_state()['alive'])

    @unittest.skipUnless(os.name == 'nt', 'Windows console lifetime regression')
    def test_ac_8_4_cli_child_has_no_console_of_its_own(self):
        with patch.object(stack, 'cli_command', return_value=(['stripe'], {})), \
                patch.object(stack.subprocess, 'Popen', side_effect=runtime.Failure('STOP_BEFORE_LAUNCH')) as process:
            with self.assertRaises(runtime.Failure):
                stack.supervise('offline-token', 'sbx_test')
            self.assertEqual(stack.subprocess.CREATE_NO_WINDOW, process.call_args.kwargs.get('creationflags'))

    def test_ac_8_4_compose_always_has_fixed_project_and_explicit_env(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(runtime, 'STATE', Path(directory)):
            args = runtime.compose_args('up', '-d')
            self.assertEqual('ledgerlab-sandbox', args[args.index('-p') + 1])
            self.assertEqual('sandbox', args[args.index('--profile') + 1])
            self.assertEqual('', Path(args[args.index('--env-file') + 1]).read_text())

    def test_ac_8_4_build_resolves_wrapper_explicitly_from_repo(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(stack, 'STATE', Path(directory)), \
                patch.object(stack, 'run_process', return_value=SimpleNamespace(returncode=0, stdout='', stderr='')) as run:
            stack.build_jars()
            for call in run.call_args_list:
                command = call.args[0]
                self.assertIn('.\\mvnw.cmd' if os.name == 'nt' else './mvnw', command)


if __name__ == '__main__':
    unittest.main()
