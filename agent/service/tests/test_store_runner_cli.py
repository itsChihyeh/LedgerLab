import contextlib
import io
import json
import re
import unittest
from datetime import datetime, timezone
from pathlib import Path

from ledgerlab_agent_service.cli import main
from ledgerlab_agent_service.runner import new_run_id
from ledgerlab_agent_service.store import JsonlStore

from tests.support import DEV_DIR, temp_store


def record(run_id_suffix: str, issue_id: str = "iss_a") -> dict:
    return {"runId": "inv_" + run_id_suffix.rjust(26, "0"), "issueId": issue_id, "status": "ok", "note": "中文"}


class StoreTest(unittest.TestCase):
    def test_ac_11_1_unreadable_lines_are_skipped_and_reported(self) -> None:
        path = temp_store(self)
        good = record("1")
        path.parent.mkdir(parents=True)
        path.write_bytes(
            b"garbage\n"
            + json.dumps(good).encode()
            + b"\n[1,2]\n"
            + b'{"runId":"x","issueId":"iss_a"}\n'
            + b"\xff\xfe\n"
            + b"\n"
        )
        stderr = io.StringIO()
        store = JsonlStore(path, stderr)
        self.assertEqual(store.for_issue("iss_a"), [good])
        self.assertEqual(stderr.getvalue().count("skipped unreadable line"), 4)

    def test_ac_11_1_records_are_only_appended_and_earlier_bytes_never_change(self) -> None:
        path = temp_store(self)
        store = JsonlStore(path, io.StringIO())
        store.append(record("1"))
        first = path.read_bytes()
        store.append(record("2"))
        second = path.read_bytes()
        self.assertTrue(second.startswith(first))
        self.assertEqual(len(second.splitlines()), 2)
        self.assertIn("中文".encode("utf-8"), first)  # written as UTF-8, not escaped
        self.assertNotIn(b"\r", second)

    def test_ac_11_1_a_torn_last_line_is_kept_and_the_next_record_starts_on_a_new_line(self) -> None:
        path = temp_store(self)
        path.parent.mkdir(parents=True)
        torn = json.dumps(record("1")).encode() + b'\n{"runId": "inv_'
        path.write_bytes(torn)
        store = JsonlStore(path, io.StringIO())
        store.append(record("2"))
        data = path.read_bytes()
        self.assertTrue(data.startswith(torn))
        reopened = JsonlStore(path, io.StringIO())
        self.assertEqual([r["runId"] for r in reopened.for_issue("iss_a")], [record("2")["runId"], record("1")["runId"]])

    def test_ac_11_1_the_parent_directory_is_created(self) -> None:
        path = temp_store(self)
        self.assertFalse(path.parent.exists())
        JsonlStore(path, io.StringIO())
        self.assertTrue(path.parent.is_dir())


class RunIdTest(unittest.TestCase):
    def test_ac_11_1_run_id_is_a_ulid_from_the_injected_time_and_randomness(self) -> None:
        run_id = new_run_id(0, 0)
        self.assertEqual(run_id, "inv_" + "0" * 26)
        stamp = int(datetime(2026, 11, 6, tzinfo=timezone.utc).timestamp() * 1000)
        a, b = new_run_id(stamp, 5), new_run_id(stamp + 1, 0)
        self.assertRegex(a, r"^inv_[0-9A-HJKMNP-TV-Z]{26}$")
        self.assertEqual(a, new_run_id(stamp, 5))
        self.assertLess(a, b)
        self.assertEqual(new_run_id(0, 1), "inv_" + "0" * 25 + "1")
        self.assertEqual(new_run_id(1, 0), "inv_" + "0" * 9 + "1" + "0" * 16)
        self.assertTrue(new_run_id((1 << 48) - 1, (1 << 80) - 1).startswith("inv_7ZZZ"))


class CliArgumentsTest(unittest.TestCase):
    def run_main(self, *argv: str) -> tuple[int, str]:
        err = io.StringIO()
        with contextlib.redirect_stderr(io.StringIO()):
            try:
                code = main(list(argv), err)
            except SystemExit as exit_:
                code = int(exit_.code)
        return code, err.getvalue()

    def base(self, store: Path) -> list[str]:
        return ["--snapshot", str(DEV_DIR / "snapshot.json"), "--model", "stub", "--store", str(store), "--port", "0"]

    def test_ac_11_1_a_non_loopback_host_is_refused_with_exit_code_2(self) -> None:
        store = temp_store(self)
        for host in ("0.0.0.0", "192.168.1.10", "::", "example.com", ""):
            code, message = self.run_main(*self.base(store), "--host", host)
            self.assertEqual(code, 2, host)
        self.assertIn("loopback", message)
        self.assertFalse(store.exists())

    def test_ac_11_1_a_base_url_with_credentials_or_a_bad_scheme_is_refused_with_exit_code_2(self) -> None:
        store = temp_store(self)
        for url in ("http://user:pw@127.0.0.1:8080", "ftp://127.0.0.1", "http://127.0.0.1:8080?x=1"):
            code, message = self.run_main("--base-url", url, "--model", "stub", "--store", str(store), "--port", "0")
            self.assertEqual(code, 2, url)
            self.assertNotIn("pw", message)

    def test_ac_11_1_bad_model_missing_source_and_both_sources_exit_with_code_2(self) -> None:
        store = temp_store(self)
        snapshot = str(DEV_DIR / "snapshot.json")
        cases = [
            ["--snapshot", snapshot, "--model", "gpt", "--port", "0"],
            ["--snapshot", snapshot, "--model", "scripted:/no/such/file.json", "--port", "0"],
            ["--snapshot", snapshot, "--model", "stub", "--port", "70000"],
            ["--model", "stub"],
            ["--snapshot", snapshot, "--base-url", "http://127.0.0.1:1", "--model", "stub"],
            ["--snapshot", str(DEV_DIR / "missing.json"), "--model", "stub", "--port", "0"],
        ]
        for argv in cases:
            self.assertEqual(self.run_main(*argv, "--store", str(store))[0], 2, argv)


if __name__ == "__main__":
    unittest.main()
