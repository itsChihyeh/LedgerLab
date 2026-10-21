#!/bin/sh
# Project skills live in .agents/skills/ (read by Codex). Claude Code only discovers
# .claude/skills/, so it keeps an identical copy. Edit .agents/skills/ and run
# `scripts/check-skills-sync.sh --fix` to refresh the copy; CI and pre-commit fail on drift.
set -eu
if [ "${1:-}" = "--fix" ]; then
  rm -rf .claude/skills
  cp -R .agents/skills .claude/skills
fi
if ! diff -r .agents/skills .claude/skills; then
  echo ".claude/skills differs from .agents/skills. Run scripts/check-skills-sync.sh --fix." >&2
  exit 1
fi
