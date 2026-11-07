import contextlib
import io
import json
import unittest

from ledgerlab_agent.cli import main
from tests.dev_data import DEV_DIR, load_dev_labels


class CliBaselineAndStubTest(unittest.TestCase):
    def run_cli(self, model: str, issue_id: str) -> tuple[int, dict[str, object]]:
        out, err = io.StringIO(), io.StringIO()
        argv = ["investigate", issue_id, "--snapshot", str(DEV_DIR / "snapshot.json"), "--model", model, "--json"]
        code = main(argv, out, err)
        return code, json.loads(out.getvalue()) if out.getvalue() else {}

    def test_ac_10_7_baseline_and_stub_answer_from_a_snapshot_without_a_backend(self) -> None:
        label = next(x for x in load_dev_labels() if x["root_cause"] == "BANK_DUPLICATE_CREDIT")
        results = [self.run_cli(model, label["issue_id"]) for model in ("baseline", "stub")]
        for code, concl in results:
            self.assertEqual(code, 0)
            self.assertEqual(concl["root_cause"], "BANK_DUPLICATE_CREDIT")
        self.assertEqual(results[0][1], results[1][1])

    def test_ac_10_7_a_source_is_required_and_the_two_sources_exclude_each_other(self) -> None:
        for argv in (
            ["investigate", "iss_x", "--model", "stub"],
            ["investigate", "iss_x", "--model", "stub", "--base-url", "http://127.0.0.1:1", "--snapshot", "x.json"],
        ):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                main(argv, io.StringIO(), io.StringIO())


if __name__ == "__main__":
    unittest.main()
