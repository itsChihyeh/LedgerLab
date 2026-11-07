"""Command line around the evaluator: runs splits, writes reports, keeps the test-set run log.

    python agent/ledgerlab-agent.py eval --split dev|test|hard --model baseline|stub [--out result.json]
    python agent/ledgerlab-agent.py eval-report [--out-dir docs/eval] [--date YYYY-MM-DD] [--check]

All file writes of the evaluation live here (agent/src is write-free, see tests/test_static.py).

Test-set discipline (docs/design/investigation-agent.md section 8.4): every evaluation of the test split first makes
sure `agent/eval/data/test/runs.log` has a line for this (model configuration, code fingerprint); the hard split, which
is kept for reporting only, has the same kind of log in `agent/eval/data/hard/runs.log`. The fingerprint is a
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
import time
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any, TextIO

AGENT_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = AGENT_DIR.parent
for _path in (AGENT_DIR, AGENT_DIR / "src"):
    if str(_path) not in sys.path:
        sys.path.insert(0, str(_path))

from ledgerlab_agent import evaluate, external  # noqa: E402
from ledgerlab_agent.cli import add_external_arguments  # noqa: E402
from ledgerlab_agent.deepseek_model import Transport as ModelTransport  # noqa: E402
from ledgerlab_agent.model import ModelError  # noqa: E402
from ledgerlab_agent.pricing import PRICING_PATH, Prices, estimate_run_usd  # noqa: E402
from ledgerlab_agent.sop import SopIndex, load_sop  # noqa: E402

RUNS_LOG = AGENT_DIR / "eval" / "data" / "test" / "runs.log"
HARD_RUNS_LOG = AGENT_DIR / "eval" / "data" / "hard" / "runs.log"
LOGGED_SPLITS: dict[str, Path] = {"test": RUNS_LOG, "hard": HARD_RUNS_LOG}
RUNS_LOG_HEADER = "# utc_time\tmodel\tcode_fingerprint\tcommit"
FINGERPRINT_SUFFIXES = {".py", ".md", ".json"}
REPRODUCE = (
    "sh scripts/agent/eval.sh           # run everything and regenerate this report",
    "sh scripts/agent/eval.sh --check  # same runs, no report, exit 1 when a hard line fails",
    "python agent/ledgerlab-agent.py eval --split dev --model baseline",
    "python agent/ledgerlab-agent.py eval --split dev --model stub",
    "python agent/ledgerlab-agent.py eval --split test --model baseline",
    "python agent/ledgerlab-agent.py eval --split test --model stub",
    "python agent/ledgerlab-agent.py eval --split hard --model baseline",
    "python agent/ledgerlab-agent.py eval --split hard --model stub",
    "sh scripts/agent/verify-eval.sh --split hard   # regenerate the hard set from its seed and compare (needs Docker)",
)

EXTERNAL_REPRODUCE = (
    "# external model (billed; needs DEEPSEEK_API_KEY, verified prices in agent/pricing.json, a USD cap):",
    "python agent/ledgerlab-agent.py eval --split test --model deepseek:<model> --reasoning-effort high \\",
    "    --confirm-spend <USD cap> --out docs/eval/deepseek-test.json",
    "sh scripts/agent/eval.sh --external docs/eval/deepseek-dev.json docs/eval/deepseek-test.json docs/eval/deepseek-hard.json",
)

# Written by hand after reading the numbers of the report this was last run for; shown under the failure modes.
HARD_INTERPRETATION: tuple[str, ...] = (
    "规则基线在难题集上没有一次答错，所有失分都是拒答：26 道连带差异题和 6 道放在连带差异上的注入题全部拒答"
    "（规则只认主类别，其余类别按 SOP-2 交人处理）。它诚实但帮不上忙。",
    "14 道组合题全对：同一提现叠两种主类别故障，规则已经够用，难处不在组合，而在要从别的差异反推出造成它的原因。",
    "所以这一列真正能拉开差距的是 32/46 的拒答：外部模型若能凭银行流水和批次推出少到账、拆分、币种错标等，就是增量；"
    "但它也可能答错，比较时要同时看错答数（基线为 0），不能只看准确率。",
    "注入题基线全部拒答，没有机会被带偏，这个指标对基线没有区分力；这批新写法的注入文字要到外部模型上才有意义。",
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


def log_split_use(split_name: str, model: str, now: Callable[[], dt.datetime], log_path: Path | None = None) -> None:
    """The test and hard splits are logged whenever a model configuration is evaluated on them (section 8.4)."""
    if split_name in LOGGED_SPLITS:
        commit, dirty = git_commit()
        record_test_run(
            model, code_fingerprint(), commit + ("+uncommitted" if dirty else ""), now, log_path or LOGGED_SPLITS[split_name]
        )


def run_one(
    split_name: str,
    model: str,
    sop: SopIndex,
    now: Callable[[], dt.datetime],
    log_path: Path | None = None,
    data_dir: Path = evaluate.DATA_DIR,
) -> evaluate.RunResult:
    log_split_use(split_name, model, now, log_path)
    return evaluate.run_split(evaluate.load_split(split_name, data_dir), model, sop)


def run_external(
    args: argparse.Namespace,
    sop: SopIndex,
    now: Callable[[], dt.datetime],
    out: TextIO,
    err: TextIO,
    model_transport: ModelTransport | None = None,
    environ: Mapping[str, str] | None = None,
    pricing_path: Path = PRICING_PATH,
    sleep: Callable[[float], None] = time.sleep,
    log_path: Path | None = None,
    data_dir: Path = evaluate.DATA_DIR,
) -> int:
    """`eval --model deepseek:<model>`: every guard first (verified price, printed estimate, --confirm-spend, key,
    somewhere to write the result), then the run, which stops as soon as the cap is exceeded."""
    if args.out is None:
        raise ValueError("an external run needs --out <result.json>: a paid run must leave its result somewhere")
    if args.limit is not None and args.limit < 1:
        raise ValueError("--limit must be at least 1")
    config = external.parse_spec(args.model, args.reasoning_effort, args.max_tokens)
    split = evaluate.load_split(args.split, data_dir)
    # The stand-in replays the same questions locally; its (estimated) input tokens size the estimate.
    stub = evaluate.run_split(split, "stub", sop, limit=args.limit)

    def estimate(prices: Prices) -> float:
        return estimate_run_usd(
            stub.metrics["questions"], stub.metrics["tokens"]["input"], prices, args.est_input_multiplier, args.est_output_tokens
        )

    guarded, prices = external.prepare(config, args.confirm_spend, estimate, err, model_transport, environ, sleep, pricing_path)
    log_split_use(args.split, config.label, now, log_path)
    run = evaluate.run_split(split, config.label, sop, client=guarded, limit=args.limit)
    commit, dirty = git_commit()
    details = {
        "label": config.label,
        "provider": "deepseek",
        "model": config.model,
        "reasoning_effort": config.reasoning_effort,
        "max_tokens": config.max_tokens,
        "limit": args.limit,
        "price": {
            "input_cache_hit": prices.input_cache_hit,
            "input_cache_miss": prices.input_cache_miss,
            "output": prices.output,
            "source_url": prices.source_url,
            "checked_at": prices.checked_at,
        },
        "spend_cap_usd": guarded.guard.cap_usd,
        "cost_usd": guarded.guard.spent_usd,
        "date": now().date().isoformat(),
        "commit": commit + ("+uncommitted" if dirty else ""),
        "code_fingerprint": code_fingerprint(),
    }
    _write(args.out, json.dumps(evaluate.external_document(run, details), ensure_ascii=False, indent=2) + "\n")
    print(_summary(run), file=out)
    print(f"spent {guarded.guard.spent_usd:.4f} USD of the {guarded.guard.cap_usd:.4f} USD cap; wrote {args.out}", file=out)
    if not run.completed:
        print(f"INCOMPLETE ({run.incomplete_reason}): {len(run.questions)} of {len(split.labels if args.limit is None else split.labels[: args.limit])} questions", file=err)
        return 3
    return 0


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
            f"  tool calls mean {evaluate._num(m['tool_calls']['mean'])}, tokens {m['tokens']['input']}/{m['tokens']['output']}"
            f"{' (estimate)' if m['tokens']['is_estimate'] else ''}",
        ]
    )


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="ledgerlab-agent")
    sub = parser.add_subparsers(dest="command", required=True)
    one = sub.add_parser("eval", help="evaluate one model configuration on one split (replay mode)")
    one.add_argument("--split", choices=evaluate.SPLITS, required=True)
    one.add_argument("--model", required=True, help="baseline | stub | deepseek:<model> (billed; needs --confirm-spend)")
    one.add_argument("--out", type=Path, help="write this run's metrics and per-question rows as JSON (required for deepseek)")
    add_external_arguments(one)
    one.add_argument("--limit", type=int, metavar="N", help="external models only: run just the first N questions (smoke test)")
    one.add_argument("--est-input-multiplier", type=float, default=external.DEFAULT_INPUT_MULTIPLIER)
    one.add_argument("--est-output-tokens", type=int, default=external.DEFAULT_OUTPUT_TOKENS_PER_QUESTION)
    rep = sub.add_parser("eval-report", help="run baseline and stand-in on dev, test and hard, write docs/eval/agent-<date>.md/.json")
    rep.add_argument("--external", type=Path, nargs="*", default=[], help="result files written by `eval --model deepseek:...`")
    rep.add_argument("--out-dir", type=Path, default=REPO_ROOT / "docs" / "eval")
    rep.add_argument("--date", help="report date, default today (local)")
    rep.add_argument("--check", action="store_true", help="write nothing; exit 1 when a hard line fails")
    return parser


def main(
    argv: Sequence[str] | None = None,
    stdout: TextIO | None = None,
    stderr: TextIO | None = None,
    now: Callable[[], dt.datetime] = lambda: dt.datetime.now().astimezone(),
    model_transport: ModelTransport | None = None,
    environ: Mapping[str, str] | None = None,
    pricing_path: Path = PRICING_PATH,
    sleep: Callable[[float], None] = time.sleep,
    log_dir: Path | None = None,
) -> int:
    """`model_transport`, `environ`, `pricing_path`, `sleep` and `log_dir` (where the test and hard run logs live) are
    there so tests can run the external path without a network, a key or the real logs."""
    out, err = stdout or sys.stdout, stderr or sys.stderr
    args = _parser().parse_args(argv)
    sop = load_sop()
    try:
        if args.command == "eval" and external.is_external(args.model):
            log = log_dir / "runs.log" if log_dir else None
            return run_external(args, sop, now, out, err, model_transport, environ, pricing_path, sleep, log)
        if args.command == "eval":
            if args.model not in evaluate.MODELS:
                raise ValueError(f"unknown model {args.model!r}; available: {', '.join((*evaluate.MODELS, 'deepseek:<model>'))}")
            run = run_one(args.split, args.model, sop, now)
            print(_summary(run), file=out)
            if args.out:
                document = {"metrics": run.metrics, "questions": evaluate.question_rows(run)}
                _write(args.out, json.dumps(document, ensure_ascii=False, indent=2) + "\n")
            return 0
        return _report(args, sop, now, out, err)
    except (ValueError, OSError, ModelError) as exc:
        print(f"error: {exc}", file=err)
        return 2


def load_external_documents(paths: Sequence[Path]) -> list[dict[str, Any]]:
    documents = [json.loads(path.read_text(encoding="utf-8")) for path in paths]
    for path, doc in zip(paths, documents, strict=True):
        if not isinstance(doc, dict) or doc.get("kind") != "external_run":
            raise ValueError(f"{path} is not a result written by `eval --model deepseek:...`")
    return documents


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
    external_documents = load_external_documents(args.external)
    meta: dict[str, Any] = {
        "date": date,
        "commit": commit + (" (working tree had uncommitted changes)" if dirty else ""),
        "code_fingerprint": code_fingerprint(),
        "reproduce": [*REPRODUCE, *(EXTERNAL_REPRODUCE if external_documents else ())],
        "hard_interpretation": list(HARD_INTERPRETATION),
    }
    report = evaluate.build_report(runs, meta, read_runs_log(), read_runs_log(HARD_RUNS_LOG), external_documents)
    base = args.out_dir / f"agent-{date}"
    _write(base.with_suffix(".json"), json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    _write(base.with_suffix(".md"), evaluate.render_markdown(report))
    print(f"wrote {base.with_suffix('.md')} and {base.with_suffix('.json')}", file=out)
    return 1 if failures else 0
