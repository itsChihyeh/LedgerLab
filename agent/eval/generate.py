"""P8-T04 evaluation set generator: one disposable stack per split, a seeded set of faulted payouts, a crawled
snapshot and labels that record what was injected.

    python agent/eval/generate.py --split dev|test|hard|all [--out-dir agent/eval/data] [--force]

Writes <out-dir>/<split>/{snapshot.json,labels.json,manifest.json}. Existing frozen data is only replaced with --force.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from collections.abc import Callable
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

AGENT_DIR = Path(__file__).resolve().parents[1]
for _path in (AGENT_DIR, AGENT_DIR / "src"):
    if str(_path) not in sys.path:
        sys.path.insert(0, str(_path))

from eval import catalog, dataset, probe  # noqa: E402
from eval.crawl import CrawlResult, crawl, entries_size  # noqa: E402
from eval.scenario import Scenario, ScenarioError  # noqa: E402
from eval.stack import APP_JAR, REPO_ROOT, SIMULATOR_JAR, Stack  # noqa: E402
from ledgerlab_agent.records import Record  # noqa: E402
from ledgerlab_agent.replay import RequestKey, SnapshotEntry, parse_snapshot, snapshot_document  # noqa: E402
from ledgerlab_agent.transport import HttpTransport  # noqa: E402

DATA_DIR = AGENT_DIR / "eval" / "data"
FILES = ("snapshot.json", "labels.json", "manifest.json")
MAX_SNAPSHOT_BYTES = 5 * 1024 * 1024
MANIFEST_FORMAT = 1


def log(message: str) -> None:
    print(message, file=sys.stderr, flush=True)


# ---- driving the stack -------------------------------------------------------------------------------------------
def run_plan(s: Scenario, plan: catalog.Plan, state: dict[str, Any]) -> None:
    """Create every payout (group by group, on its own simulator day), load the evidence and run reconciliation once."""
    for index, group in enumerate(catalog.GROUP_ORDER):
        members = [p for p in plan.payouts if p.group == group]
        if not members:
            continue
        if index == 0:
            if not s.sim_now().isoformat().startswith("2026-10-19"):
                raise ScenarioError(f"Expected a fresh simulator on 2026-10-19, clock is {s.sim_now().isoformat()}")
        else:
            s.advance_to(catalog.GROUP_CLOCK[group])
        log(f"group {group}: {len(members)} payouts")
        for number, payout in enumerate(members, start=1):
            probe.prepare_case(s, payout.case, state)
            if number % 10 == 0:
                log(f"  {number}/{len(members)}")
    s.advance_to(catalog.CLOCK_BEFORE_IMPORTS)
    for business_date in catalog.SNAPSHOT_DATES:
        status, body = s.processor_snapshot(business_date)
        if status != 200 or not isinstance(body, dict) or body.get("status") != "ACCEPTED":
            raise ScenarioError(f"Processor snapshot {business_date} was not accepted: HTTP {status} {str(body)[:200]}")
    for day in catalog.BANK_DAYS:
        status, raw = s.export_bank_statement(day)
        if status != 200:
            raise ScenarioError(f"Bank statement export {day}: HTTP {status}")
        i_status, body = s.import_bank_statement(raw)
        if i_status not in (200, 201) or not isinstance(body, dict) or body.get("status") != "ACCEPTED":
            raise ScenarioError(f"Bank statement import {day} was not accepted: HTTP {i_status} {str(body)[:200]}")
    evidence = s.evidence_boundary()
    log("waiting for the evidence grace period")
    s.wait_for_evidence_grace(evidence)
    status, report = s.run_reconciliation(catalog.CUTOFF, evidence)
    if status != 200:
        raise ScenarioError(f"Reconciliation run: HTTP {status} {str(report)[:300]}")
    kinds = [f"{r.get('kind')}:{r.get('status')}" for r in (report or {}).get("runs", [])]
    log(f"reconciliation run: {', '.join(kinds)}")


def collect(s: Scenario, base_url: str, log_fn: Callable[[str], None]) -> tuple[list[dataset.IssueView], CrawlResult]:
    listing = s.list_all_issues(probe.ISSUE_CATEGORIES)
    ids = sorted(str(item.get("id") or item.get("issueId")) for item in listing)
    log_fn(f"{len(ids)} issues; fetching details and crawling")
    views = [dataset.issue_view(s.get_issue(issue_id)) for issue_id in ids]
    result = crawl(HttpTransport(base_url), [v.id for v in views], log=log_fn)
    if result.failures:
        raise ScenarioError("crawl failed requests:\n  " + "\n  ".join(result.failures[:20]))
    return views, result


# ---- bookkeeping --------------------------------------------------------------------------------------------------
def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=REPO_ROOT, capture_output=True, text=True, check=True).stdout.strip()


def jar_info(path: Path) -> dict[str, Any]:
    full = REPO_ROOT / path
    digest = hashlib.sha256()
    with full.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return {"path": str(path).replace("\\", "/"), "bytes": full.stat().st_size, "sha256": digest.hexdigest()}


def compose_images() -> list[str]:
    text = (REPO_ROOT / "compose.yaml").read_text(encoding="utf-8")
    return sorted(set(re.findall(r"^\s*image:\s*(\S+)", text, re.M)))


def sort_entries(entries: dict[RequestKey, SnapshotEntry]) -> list[tuple[RequestKey, SnapshotEntry]]:
    return sorted(entries.items(), key=lambda item: item[0])


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")


def render_snapshot(document: dict[str, Any]) -> str:
    """One request per line, so a regenerated snapshot diffs by request."""
    head = {k: v for k, v in document.items() if k != "requests"}
    lines = [json.dumps(item, ensure_ascii=False, separators=(",", ":")) for item in document["requests"]]
    prefix = json.dumps(head, ensure_ascii=False, separators=(",", ":"))[:-1]
    return prefix + ',"requests":[\n' + ",\n".join(lines) + "\n]}\n"


class Capture:
    """Everything read from one stack: payout facts, issue details and the crawled snapshot."""

    def __init__(self, facts: dict[str, dict[str, str]], views: list[dataset.IssueView], crawled: CrawlResult) -> None:
        self.facts, self.views, self.crawled = facts, views, crawled

    def to_json(self) -> dict[str, Any]:
        return {
            "facts": self.facts,
            "views": [v.__dict__ for v in self.views],
            "entries": snapshot_document(sort_entries(self.crawled.entries), "", ""),
            "reached": {k: sorted((r.kind, r.id) for r in v) for k, v in self.crawled.reached.items()},
        }

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> Capture:
        snapshot = parse_snapshot(data["entries"])
        reached = {k: {Record(kind, ident) for kind, ident in v} for k, v in data["reached"].items()}
        return cls(data["facts"], [dataset.IssueView(**v) for v in data["views"]],
                   CrawlResult(dict(snapshot.entries), reached))


def capture_split(split_name: str, stack_factory: Callable[[], Stack] = Stack) -> Capture:
    plan = catalog.build_plan(split_name)
    for path in (APP_JAR, SIMULATOR_JAR):
        if not (REPO_ROOT / path).is_file():
            raise ScenarioError(f"Missing {path}; build the jars first (see scripts/agent/probe.sh)")
    state = probe.new_state()
    with stack_factory() as stack:
        s = Scenario(stack.app, stack.sim)
        run_plan(s, plan, state)
        views, crawled = collect(s, "http://" + stack.app, log)
    facts = {key: {"payout": entry["payout"], "charge": entry["charge"]} for key, entry in state["cases"].items()}
    return Capture(facts, views, crawled)


def finish_split(split_name: str, captured: Capture, out_dir: Path, command: str) -> dict[str, Any]:
    plan = catalog.build_plan(split_name)
    config = plan.split
    views, crawled = captured.views, captured.crawled
    labels = dataset.build_labels(plan, captured.facts, views, crawled)
    problems = (dataset.validate_hard_composition if config.hard else dataset.validate_composition)(labels)
    if problems:
        raise dataset.DatasetError("composition problems:\n  " + "\n  ".join(problems))

    generated_at = datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")
    commit = git("rev-parse", "HEAD")
    dirty = bool(git("status", "--porcelain", "--", ".", ":(exclude)agent/eval/data"))
    document = snapshot_document(sort_entries(crawled.entries), generated_at, commit)
    snapshot_text = render_snapshot(document)
    if len(snapshot_text.encode("utf-8")) > MAX_SNAPSHOT_BYTES:
        raise dataset.DatasetError(
            f"snapshot is {len(snapshot_text.encode('utf-8'))} bytes, over the {MAX_SNAPSHOT_BYTES} byte limit; "
            "shrink the crawl or compress (needs a decision, see the T04 task)"
        )
    digest = dataset.issue_digest(views)
    manifest = {
        "format": MANIFEST_FORMAT,
        "split": split_name,
        "command": command,
        "seed": config.seed,
        **({"note": config.note} if config.note else {}),
        "amount_range_cents": [config.amount_min, config.amount_max],
        "generated_at": generated_at,
        "commit": commit,
        "tree_dirty_outside_data": dirty,
        "stack": {"app_jar": jar_info(APP_JAR), "simulator_jar": jar_info(SIMULATOR_JAR), "images": compose_images()},
        "time_plan": {
            "groups": catalog.GROUP_CLOCK,
            "processor_snapshot_dates": list(catalog.SNAPSHOT_DATES),
            "bank_statement_days": list(catalog.BANK_DAYS),
            "reconciliation_cutoff": catalog.CUTOFF,
        },
        "counts": {
            **dataset.composition_counts(labels),
            "payouts": len(plan.payouts),
            "issues_in_snapshot": len(views),
            "issues_with_a_question": len({label["issue_id"] for label in labels}),
            "snapshot_requests": len(crawled.entries),
            "snapshot_body_bytes": entries_size(crawled.entries),
            "snapshot_file_bytes": len(snapshot_text.encode("utf-8")),
        },
        "issue_digest": digest,
    }
    target = out_dir / split_name
    write_text(target / "snapshot.json", snapshot_text)
    write_text(
        target / "labels.json",
        json.dumps({"format": dataset.LABELS_FORMAT, "split": split_name, "labels": labels}, indent=2, ensure_ascii=False) + "\n",
    )
    write_text(target / "manifest.json", json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
    log(f"wrote {target} ({len(labels)} questions, {len(views)} issues, {len(crawled.entries)} requests)")
    return manifest


def generate_split(
    split_name: str,
    out_dir: Path,
    command: str,
    stack_factory: Callable[[], Stack] = Stack,
    raw_dir: Path | None = None,
) -> dict[str, Any]:
    captured = capture_split(split_name, stack_factory)
    if raw_dir is not None:  # Kept so a labelling problem can be fixed without another 15 minutes of stack time.
        write_text(raw_dir / f"capture-{split_name}.json", json.dumps(captured.to_json(), ensure_ascii=False))
    return finish_split(split_name, captured, out_dir, command)


def main(argv: list[str] | None = None, stack_factory: Callable[[], Stack] = Stack) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--split", choices=("dev", "test", "hard", "all"), default="all")
    parser.add_argument("--out-dir", type=Path, default=DATA_DIR)
    parser.add_argument("--force", action="store_true", help="replace already frozen data")
    parser.add_argument("--raw-dir", type=Path, default=REPO_ROOT / "target" / "agent-eval")
    parser.add_argument("--from-capture", type=Path, help="rebuild labels and files from a saved capture (one split)")
    args = parser.parse_args(argv)
    splits = list(catalog.STANDARD_SPLITS) if args.split == "all" else [args.split]  # "all" never touches hard
    existing = [name for name in splits if any((args.out_dir / name / f).exists() for f in FILES)]
    if existing and not args.force:
        log(f"Frozen data already exists for: {', '.join(existing)}. Use --force to overwrite it.")
        return 2
    for name in splits:
        command = f"sh scripts/agent/generate-eval.sh --split {name}"
        try:
            if args.from_capture is not None:
                captured = Capture.from_json(json.loads(args.from_capture.read_text(encoding="utf-8")))
                finish_split(name, captured, args.out_dir, command)
                continue
            generate_split(name, args.out_dir, command, stack_factory, args.raw_dir)
        except (ScenarioError, dataset.DatasetError, RuntimeError, subprocess.CalledProcessError) as error:
            log(f"generation of {name} failed: {error}")
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
