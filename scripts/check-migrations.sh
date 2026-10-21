#!/bin/sh
# Committed Flyway migrations are immutable (AGENTS.md rule 4, ADR-0003).
# Fails if a change modifies, deletes or renames an existing file under any db/migration/ directory.
# Adding new migration files is fine. Runs in lefthook (pre-commit) and CI, so it covers every
# agent and every way of editing, not only Claude's file tools.
#
# Usage:
#   scripts/check-migrations.sh --staged   # staged changes against HEAD (pre-commit)
#   scripts/check-migrations.sh <base>     # commits in <base>..HEAD (CI)
set -eu

if [ "${1:-}" = "--staged" ]; then
  git rev-parse --verify -q HEAD >/dev/null || exit 0   # first commit: nothing is committed yet
  changes=$(git diff --cached --no-renames --name-status --diff-filter=DMT -- ':(icase,glob)**/db/migration/**')
else
  base=${1:?usage: check-migrations.sh --staged | <base-rev>}
  changes=$(git diff --no-renames --name-status --diff-filter=DMT "$base" HEAD -- ':(icase,glob)**/db/migration/**')
fi

if [ -n "$changes" ]; then
  echo "Committed Flyway migrations must not be modified, deleted or renamed:" >&2
  echo "$changes" >&2
  echo "Add a new V<n>__<desc>.sql migration instead." >&2
  exit 1
fi
