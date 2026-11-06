"""AC-10.5 check: regenerate a split from the manifest's seed and compare it with the committed data, ignoring ids.

    python agent/eval/verify_dataset.py --split dev [--data-dir agent/eval/data]

Exit code 0 when labels and the issue set are the same up to generated identifiers.
"""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
from collections.abc import Callable
from pathlib import Path
from typing import Any

AGENT_DIR = Path(__file__).resolve().parents[1]
for _path in (AGENT_DIR, AGENT_DIR / "src"):
    if str(_path) not in sys.path:
        sys.path.insert(0, str(_path))

from eval import catalog, dataset, generate  # noqa: E402
from eval.stack import REPO_ROOT, Stack  # noqa: E402


def load_normalized(directory: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    labels = json.loads((directory / "labels.json").read_text(encoding="utf-8"))["labels"]
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    return dataset.normalized(labels, manifest["issue_digest"]), manifest


def compare_directories(committed: Path, regenerated: Path) -> list[str]:
    """Differences between two data directories (empty when they agree up to ids)."""
    left, left_manifest = load_normalized(committed)
    right, right_manifest = load_normalized(regenerated)
    problems = dataset.differences(left, right)
    for key in ("seed", "amount_range_cents", "time_plan"):
        if left_manifest[key] != right_manifest[key]:
            problems.append(f"manifest {key} differs")
    counts_left, counts_right = left_manifest["counts"], right_manifest["counts"]
    for key in ("questions", "by_kind", "single_by_root_cause", "payouts", "issues_in_snapshot", "issues_with_a_question"):
        if counts_left[key] != counts_right[key]:
            problems.append(f"manifest count {key} differs: {counts_left[key]} vs {counts_right[key]}")
    return problems


def main(argv: list[str] | None = None, stack_factory: Callable[[], Stack] = Stack) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--split", choices=("dev", "test"), required=True)
    parser.add_argument("--data-dir", type=Path, default=generate.DATA_DIR)
    args = parser.parse_args(argv)
    committed = args.data_dir / args.split
    manifest_path = committed / "manifest.json"
    if not manifest_path.is_file():
        generate.log(f"No frozen data in {committed}")
        return 2
    seed = json.loads(manifest_path.read_text(encoding="utf-8"))["seed"]
    if seed != catalog.SPLITS[args.split].seed:
        generate.log(f"Manifest seed {seed} differs from the catalog seed {catalog.SPLITS[args.split].seed}")
        return 1
    work = REPO_ROOT / "target" / "agent-eval"
    work.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=work, prefix="verify-") as scratch:
        try:
            generate.generate_split(args.split, Path(scratch), f"verify --split {args.split}", stack_factory)
        except (RuntimeError, dataset.DatasetError) as error:
            generate.log(f"regeneration failed: {error}")
            return 1
        problems = compare_directories(committed, Path(scratch) / args.split)
    if problems:
        generate.log(f"{args.split}: regenerated data differs from the committed data:")
        for problem in problems[:40]:
            generate.log("  " + problem)
        return 1
    generate.log(f"{args.split}: regenerated data matches the committed data (ids ignored)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
