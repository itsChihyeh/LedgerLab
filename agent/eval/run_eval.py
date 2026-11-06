"""Command line around the evaluator: runs splits, writes reports, keeps the test-set run log.

    python agent/ledgerlab-agent.py eval --split dev|test --model baseline|stub [--out result.json]
    python agent/ledgerlab-agent.py eval-report [--out-dir docs/eval] [--date YYYY-MM-DD] [--check]

All file writes of the evaluation live here (agent/src is write-free, see tests/test_static.py).

Test-set discipline (docs/design/investigation-agent.md section 8.4): every evaluation of the test split first makes
sure `agent/eval/data/test/runs.log` has a line for this (model configuration, code fingerprint). The fingerprint is a
hash of the agent source and the SOP, so re-running unchanged code adds nothing, while running the test set again
after any change to rules, prompts or SOP adds a line and the report shows how often the set has been looked at.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import subprocess
import sys
from collections.abc import Callable, Sequence
from pathlib import Path
from typing import Any, TextIO

AGENT_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = AGENT_DIR.parent
for _path in (AGENT_DIR, AGENT_DIR / "src"):
    if str(_path) not in sys.path:
        sys.path.insert(0, str(_path))

from ledgerlab_agent import evaluate  # noqa: E402
from ledgerlab_agent.sop import SopIndex, load_sop  # noqa: E402

RUNS_LOG = AGENT_DIR / "eval" / "data" / "test" / "runs.log"
RUNS_LOG_HEADER = "# utc_time\tmodel\tcode_fingerprint\tcommit"
FINGERPRINT_SUFFIXES = {".py", ".md", ".json"}
REPRODUCE = (
    "sh scripts/agent/eval.sh           # run everything and regenerate this report",
    "sh scripts/agent/eval.sh --check  # same runs, no report, exit 1 when a hard line fails",
    "python agent/ledgerlab-agent.py eval --split dev --model baseline",
    "python agent/ledgerlab-agent.py eval --split dev --model stub",
    "python agent/ledgerlab-agent.py eval --split test --model baseline",
    "python agent/ledgerlab-agent.py eval --split test --model stub",
)


def code_fingerprint(agent_dir: Path = AGENT_DIR) -> str:
    """Short hash of everything that decides an answer: agent source (including prompts, routes) and the SOP."""
    files = [
        p
        for p in sorted((agent_dir / "src" / "ledgerlab_agent").rglob("*"))
        if p.is_file() and p.suffix in FINGERPRINT_SUFFIXES and "__pycache__" not in p.parts
    ]
    files += sorted((agent_dir / "sop").glob("*.md"))
    digest = hashlib.sha256()
    for path in files:
        digest.update(path.relative_to(agent_dir).as_posix().encode("utf-8") + b"\0")
        digest.update(path.read_bytes().replace(b"\r\n", b"\n") + b"\0")
    return digest.hexdigest()[:12]


def git_commit(root: Path = REPO_ROOT) -> tuple[str, bool]:
    """(HEAD commit, working tree has uncommitted changes); ("unknown", False) when git is not available."""
    try:
        head = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=root, capture_output=True, text=True, check=True
        ).stdout.strip()
        dirty = bool(
            subprocess.run(
                ["git", "status", "--porcelain", "--", "."], cwd=root, capture_output=True, text=True, check=True
            ).stdout.strip()
        )
    except (OSError, subprocess.CalledProcessError):
        return "unknown", False
    return head, dirty


def read_runs_log(path: Path = RUNS_LOG) -> list[str]:
    if not path.exists():
        return []
    return [line for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def record_test_run(
    model: str,
    fingerprint: str,
    commit: str,
    now: Callable[[], dt.datetime],
    path: Path = RUNS_LOG,
) -> bool:
    """Append the (model, fingerprint) line unless it is already there; True when a line was added."""
    lines = read_runs_log(path)
    for line in lines:
        fields = line.split("\t")
        if not line.startswith("#") and len(fields) >= 3 and fields[1] == model and fields[2] == fingerprint:
            return False
    if not lines:
        lines = [RUNS_LOG_HEADER]
    stamp = now().astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    lines.append(f"{stamp}\t{model}\t{fingerprint}\t{commit}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    return True


def run_one(
    split_name: str,
    model: str,
    sop: SopIndex,
    now: Callable[[], dt.datetime],
    log_path: Path = RUNS_LOG,
    data_dir: Path = evaluate.DATA_DIR,
) -> evaluate.RunResult:
    if split_name == "test":
        commit, dirty = git_commit()
        record_test_run(model, code_fingerprint(), commit + ("+uncommitted" if dirty else ""), now, log_path)
    return evaluate.run_split(evaluate.load_split(split_name, data_dir), model, sop)


def _write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")


def _summary(run: evaluate.RunResult) -> str:
    m = run.metrics
    inj = m["injection"]
    return "\n".join(
        [
            f"{run.model} on {run.split}: {m['questions']} questions",
            f"  accuracy {m['correct']}/{m['answerable']}, abstain recall {m['abstain_hits']}/{m['abstain_expected']}, "
            f"false refusals {m['answerable_refused']}",
            f"  fabricated citations {m['fabricated_total']}, citation validity {m['citations_valid']}/{m['citations_total']}, "
            f"supported {m['supported']}/{m['support_checked']}",
            f"  injections obeyed {inj['followed_any']}/{inj['n']}, invalid outputs {m['invalid_output']}, "
            f"sop consistent {m['sop']['consistent']}/{m['sop']['checked']}",
            f"  tool calls mean {m['tool_calls']['mean']:.2f}, tokens {m['tokens']['input']}/{m['tokens']['output']}"
            f"{' (estimate)' if m['tokens']['is_estimate'] else ''}",
        ]
    )


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="ledgerlab-agent")
    sub = parser.add_subparsers(dest="command", required=True)
    one = sub.add_parser("eval", help="evaluate one model configuration on one split (replay mode)")
    one.add_argument("--split", choices=evaluate.SPLITS, required=True)
    one.add_argument("--model", choices=evaluate.MODELS, required=True)
    one.add_argument("--out", type=Path, help="write this run's metrics and per-question rows as JSON")
    rep = sub.add_parser("eval-report", help="run baseline and stand-in on dev and test, write docs/eval/agent-<date>.md/.json")
    rep.add_argument("--out-dir", type=Path, default=REPO_ROOT / "docs" / "eval")
    rep.add_argument("--date", help="report date, default today (local)")
    rep.add_argument("--check", action="store_true", help="write nothing; exit 1 when a hard line fails")
    return parser


def main(
    argv: Sequence[str] | None = None,
    stdout: TextIO | None = None,
    stderr: TextIO | None = None,
    now: Callable[[], dt.datetime] = lambda: dt.datetime.now().astimezone(),
) -> int:
    out, err = stdout or sys.stdout, stderr or sys.stderr
    args = _parser().parse_args(argv)
    sop = load_sop()
    try:
        if args.command == "eval":
            run = run_one(args.split, args.model, sop, now)
            print(_summary(run), file=out)
            if args.out:
                document = {"metrics": run.metrics, "questions": evaluate.question_rows(run)}
                _write(args.out, json.dumps(document, ensure_ascii=False, indent=2) + "\n")
            return 0
        return _report(args, sop, now, out, err)
    except (ValueError, OSError) as exc:
        print(f"error: {exc}", file=err)
        return 2


def _report(args: argparse.Namespace, sop: SopIndex, now: Callable[[], dt.datetime], out: TextIO, err: TextIO) -> int:
    runs = {
        (model, split): run_one(split, model, sop, now) for split in evaluate.SPLITS for model in evaluate.MODELS
    }
    for key in sorted(runs):
        print(_summary(runs[key]), file=out)
    failures = evaluate.hard_line_failures(runs)
    if args.check:
        for problem in failures:
            print(f"hard line failed: {problem}", file=err)
        print("hard lines hold" if not failures else f"{len(failures)} hard line failure(s)", file=out)
        return 1 if failures else 0
    commit, dirty = git_commit()
    date = args.date or now().date().isoformat()
    meta: dict[str, Any] = {
        "date": date,
        "commit": commit + (" (working tree had uncommitted changes)" if dirty else ""),
        "code_fingerprint": code_fingerprint(),
        "reproduce": list(REPRODUCE),
    }
    report = evaluate.build_report(runs, meta, read_runs_log())
    base = args.out_dir / f"agent-{date}"
    _write(base.with_suffix(".json"), json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    _write(base.with_suffix(".md"), evaluate.render_markdown(report))
    print(f"wrote {base.with_suffix('.md')} and {base.with_suffix('.json')}", file=out)
    return 1 if failures else 0
