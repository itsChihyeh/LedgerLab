"""Offline safety and parsing checks; no Stripe calls or real credentials."""
import io
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

import probe


class ProbeTests(unittest.TestCase):
    def test_ac_8_3_redaction_covers_nested_values_and_cli_errors(self):
        values = ['rk_' + 'test_' + 'Example', 'sk_' + 'live_' + 'Example',
                  'whsec_' + 'Example', 'acct_' + 'Example', 'test' + '@example.invalid']
        with patch('sys.stdout', new_callable=io.StringIO) as out:
            probe.emit('result', {'nested': values})
            for value in values:
                self.assertNotIn(value, out.getvalue())
        category = probe.error_category(400, json.dumps({'error': {
            'message': ' '.join(values), 'code': values[0], 'param': values[3],
            'type': 'invalid_request_error'}}))
        self.assertEqual('HTTP_400 type=invalid_request_error', category)

    def test_ac_8_3_payment_method_rejection_has_safe_category(self):
        body = json.dumps({'error': {'message': 'The `payment_method_types` parameter is no longer supported.',
            'type': 'invalid_request_error', 'code': 'parameter_unknown',
            'param': 'automatic_payment_methods[allow_redirects]', 'decline_code': 'generic_decline'}})
        self.assertEqual('HTTP_400 type=invalid_request_error code=parameter_unknown '
                         'param=automatic_payment_methods[allow_redirects] decline_code=generic_decline',
                         probe.error_category(400, body))

    def test_ac_8_3_missing_cli_login_skips_resend(self):
        with patch.object(probe, 'run_cli', return_value=(1, 'You have not configured API keys yet. Run stripe login')) as run:
            result = probe.cli_probe('stripe', 'synthetic', 'evt_example', login=True)
        self.assertFalse(result['secret_obtained'])
        self.assertEqual('LOGIN_REQUIRED', result['category'])
        self.assertEqual('需要用户 stripe login', result['action'])
        self.assertEqual('待用户登录后由 T04 观察', result['resend'])
        run.assert_called_once_with('stripe', 'synthetic', ['listen', '--print-secret'], True)

    def test_ac_8_3_main_succeeds_when_cli_needs_login(self):
        balance = {'livemode': False, 'available': [], 'pending': []}
        with tempfile.TemporaryDirectory() as directory, patch.object(probe, 'ROOT', Path(directory)), \
                patch.dict(os.environ, {'STRIPE_API_KEY': 'rk_' + 'test_' + 'Example', 'STRIPE_CLI_USE_LOGIN': '1'}), \
                patch.object(probe.Stripe, 'request', side_effect=[balance, balance, {'data': [], 'has_more': False}, {'data': []}]), \
                patch.object(probe.Payments, 'get', return_value={}) as get_payment, \
                patch.object(probe, 'cli_probe', return_value={'category': 'LOGIN_REQUIRED'}), \
                patch('sys.stdout', new_callable=io.StringIO):
            self.assertEqual(0, probe.main())
            self.assertEqual([(4, 1234, 'pm_card_bypassPending'), (2, 10000, 'pm_card_ca')],
                             [call.args for call in get_payment.call_args_list])

    def test_ac_8_3_rejects_live_missing_and_malformed_keys(self):
        for key in ('', 'sk_' + 'live_' + 'Example', 'rk_' + 'test_' + 'bad value'):
            with self.assertRaises(probe.ProbeError):
                probe.Stripe(key)

    def test_ac_8_3_integer_money_and_half_up(self):
        self.assertEqual(320, probe.fee_expected(10000))
        self.assertEqual(66, probe.fee_expected(1234))
        self.assertEqual(45, probe.fee_expected(500))
        for value in (True, 1.5, '100', 2**63):
            with self.assertRaises(probe.ProbeError):
                probe.integer(value)

    def test_ac_8_3_requires_explicit_false_livemode(self):
        for value in ({}, {'livemode': True}, {'livemode': 0}):
            with self.assertRaises(probe.ProbeError):
                probe.test_mode(value)

    def test_ac_8_3_login_does_not_inherit_api_key(self):
        with patch.dict(os.environ, {'STRIPE_API_KEY': 'synthetic'}):
            command, env = probe.cli_args('stripe', 'synthetic', ['listen'], login=True)
        self.assertEqual(['stripe', 'listen'], command)
        self.assertNotIn('STRIPE_API_KEY', env)
        command, _ = probe.cli_args('stripe', 'synthetic', ['listen'])
        self.assertEqual(['stripe', 'listen', '--api-key', 'synthetic'], command)

    def test_ac_8_3_no_redirect_can_forward_authorization(self):
        self.assertIsNone(probe.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.invalid'))

    def test_ac_8_3_fixed_write_scope(self):
        stripe = probe.Stripe('rk_' + 'test_' + 'Example')
        with patch.object(stripe.http, 'open', side_effect=AssertionError('No network allowed')):
            for path, number in (('/v1/refunds', 4), ('/v1/payment_intents', 1), ('/v1/payment_intents', 3)):
                with self.assertRaisesRegex(probe.ProbeError, 'UNAUTHORIZED_WRITE'):
                    stripe.request(path, {}, number)

    def test_ac_8_3_payment_slots_use_fixed_keys_and_pinned_version(self):
        stripe = probe.Stripe('rk_' + 'test_' + 'Example')
        for number in (2, 4):
            with patch.object(stripe.http, 'open', return_value=io.BytesIO(b'{"livemode": false}')) as open_request:
                stripe.request('/v1/payment_intents', {'amount': 1234}, number)
            request = open_request.call_args.args[0]
            self.assertEqual(f'probe-20261102-{number}', request.get_header('Idempotency-key'))
            self.assertEqual('2026-09-30.endive', request.get_header('Stripe-version'))
            self.assertEqual('https://api.stripe.com/v1/payment_intents', request.full_url)

    def test_ac_8_3_resume_reads_saved_object_without_second_write(self):
        class FakeStripe:
            def __init__(self):
                self.writes = []
                self.charge_type = 'card'

            def request(self, path, form=None, number=None):
                if form is not None:
                    self.writes.append((path, form, number))
                if path.startswith('/v1/payment_intents'):
                    return {'id': 'pi_example', 'amount': 1234, 'currency': 'cad',
                            'metadata': {'ledgerlab_run_id': 'probe'}, 'status': 'succeeded',
                            'livemode': False, 'latest_charge': 'ch_example'}
                if path.startswith('/v1/charges'):
                    return {'amount': 1234, 'captured': True, 'livemode': False,
                            'balance_transaction': 'txn_example', 'created': 1,
                            'payment_method_details': {'type': self.charge_type, 'card': {'country': 'US'}}}
                return {'amount': 1234, 'currency': 'cad', 'source': 'ch_example',
                        'fee': 66, 'net': 1168, 'status': 'available', 'available_on': 1}

        stripe = FakeStripe()
        now = datetime(2026, 11, 2, tzinfo=timezone.utc)
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / 'state.json'
            first = probe.Payments(stripe, state, lambda: now).get(1, 1234, 'pm_card_bypassPending')
            second = probe.Payments(stripe, state, lambda: now + timedelta(days=2)).get(1, 1234, 'pm_card_bypassPending')
            self.assertEqual(first, second)
            self.assertEqual(1, len(stripe.writes))
            self.assertEqual(1, stripe.writes[0][2])
            form = stripe.writes[0][1]
            self.assertNotIn('payment_method_types[]', form)
            self.assertEqual('true', form['automatic_payment_methods[enabled]'])
            self.assertEqual('never', form['automatic_payment_methods[allow_redirects]'])
            self.assertEqual('true', form['confirm'])
            self.assertEqual('pm_card_bypassPending', form['payment_method'])
            stripe.charge_type = 'link'
            with self.assertRaisesRegex(probe.ProbeError, 'UNEXPECTED_CHARGE_TYPE'):
                probe.Payments(stripe, state, lambda: now).get(1, 1234, 'pm_card_bypassPending')

    def test_ac_8_3_uncertain_old_write_requires_review(self):
        now = datetime(2026, 11, 2, tzinfo=timezone.utc)
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / 'state.json'
            state.write_text(json.dumps({'1': {'attempted_at': int(now.timestamp())}}))
            payments = probe.Payments(None, state, lambda: now + timedelta(hours=23))
            with self.assertRaisesRegex(probe.ProbeError, 'IDEMPOTENCY_WINDOW_EXPIRED'):
                payments.get(1, 1234, 'pm_card_bypassPending')

    def test_ac_8_3_event_lookup_follows_pages_and_matches_charge(self):
        class Pages:
            def __init__(self):
                self.paths = []

            def request(self, path):
                self.paths.append(path)
                last = len(self.paths) == 2
                return {'has_more': not last, 'data': [{'id': 'evt_target' if last else 'evt_other',
                        'livemode': False, 'data': {'object': {'id': 'ch_target' if last else 'ch_other'}}}]}

        stripe = Pages()
        self.assertEqual('evt_target', probe.find_event(stripe, {'created': 1, 'charge': 'ch_target'}))
        self.assertIn('starting_after=evt_other', stripe.paths[1])


class EnvTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shell = shutil.which('sh') or 'C:/Program Files/Git/bin/sh.exe'
        if not Path(cls.shell).exists():
            raise unittest.SkipTest('POSIX sh / Git Bash is required')

    def invoke(self, contents, extra=None):
        # Test a synthetic isolated .env, never read the user's file.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / '.env').write_bytes(contents.encode())
            env = dict(os.environ)
            env.update({'SANDBOX_ROOT': root.as_posix(), 'STRIPE_CLI': ''})
            env.pop('STRIPE_CLI_USE_LOGIN', None)
            env.update(extra or {})
            result = subprocess.run([self.shell, '-c', '. scripts/sandbox/env.sh; '
                'test "$STRIPE_API_KEY" = "rk_' + 'test_' + 'Example" || exit 99; '
                'test -z "${UNRELATED:-}" || exit 98; '
                'test "$STRIPE_CLI_USE_LOGIN" = 1 || exit 97; printf loaded'],
                env=env, capture_output=True, cwd=probe.ROOT)
            self.assertFalse((root / 'executed').exists())
            self.assertNotIn(b'rk_' + b'test_' + b'Example', result.stdout + result.stderr)
            return result

    def test_ac_8_3_literal_parsing_quotes_crlf_and_final_line(self):
        for value in ('rk_' + 'test_' + 'Example', '"rk_' + 'test_' + 'Example"', "'rk_" + "test_" + "Example'"):
            result = self.invoke('UNRELATED=should_not_load\r\nSTRIPE_API_KEY=' + value + '\r')
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(b'loaded', result.stdout)

    def test_ac_8_3_missing_duplicate_live_and_shell_syntax_rejected(self):
        for contents in ('', 'STRIPE_API_KEY=sk_' + 'live_' + 'Example',
                         'STRIPE_API_KEY=rk_' + 'test_' + 'Example\nSTRIPE_API_KEY=rk_' + 'test_' + 'Example',
                         'STRIPE_API_KEY=$(touch "$SANDBOX_ROOT/executed")'):
            self.assertEqual(3, self.invoke(contents).returncode)

    def test_ac_8_3_other_env_lines_never_execute(self):
        result = self.invoke('UNRELATED=$(touch "$SANDBOX_ROOT/executed")\n'
                             'STRIPE_API_KEY=rk_' + 'test_' + 'Example')
        self.assertEqual(0, result.returncode, result.stderr)

    def test_ac_8_3_cli_explicit_path_precedes_path_lookup(self):
        result = self.invoke('STRIPE_API_KEY=rk_' + 'test_' + 'Example', {'STRIPE_CLI': self.shell})
        self.assertEqual(0, result.returncode, result.stderr)

    def test_ac_8_3_newest_cli_uses_numeric_version_order(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for version in ('1.9.0', '1.10.0'):
                binary = root / 'ledgerlab-tools/stripe-cli' / version / 'stripe.exe'
                binary.parent.mkdir(parents=True)
                binary.touch()
            (root / '.env').write_text('STRIPE_API_KEY=rk_' + 'test_' + 'Example')
            env = dict(os.environ, SANDBOX_ROOT=root.as_posix(), LOCALAPPDATA=root.as_posix(), STRIPE_CLI='',
                       STRIPE_CLI_USE_LOGIN='0')
            result = subprocess.run([self.shell, '-c', '. scripts/sandbox/env.sh; '
                'case "$STRIPE_CLI" in */1.10.0/stripe.exe) exit 0 ;; *) exit 99 ;; esac'],
                env=env, cwd=probe.ROOT, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)


if __name__ == '__main__':
    unittest.main()
