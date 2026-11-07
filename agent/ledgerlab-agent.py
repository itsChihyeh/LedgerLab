"""Thin launcher: puts agent/src on sys.path and runs ledgerlab_agent.cli (eval commands: eval/run_eval.py)."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "src"))

EVAL_COMMANDS = {"eval", "eval-report"}  # these write files, so they live outside the write-free package

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] in EVAL_COMMANDS:
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        from eval.run_eval import main as eval_main

        raise SystemExit(eval_main())
    from ledgerlab_agent.cli import main

    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from eval.trace_files import write_trace

    raise SystemExit(main(trace_writer=write_trace))
