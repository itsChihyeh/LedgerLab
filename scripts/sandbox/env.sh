#!/bin/sh
# Source from a sandbox script after setting SANDBOX_ROOT, or from the repo.
# Never enable tracing while loading credentials (including inherited sh -x).
set +x
SANDBOX_ROOT=${SANDBOX_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null)}
STRIPE_API_KEY=
_sandbox_count=0
if [ -f "$SANDBOX_ROOT/.env" ]; then
  while IFS= read -r _sandbox_line || [ -n "$_sandbox_line" ]; do
    _sandbox_name=${_sandbox_line%%=*}
    [ "$_sandbox_name" = STRIPE_API_KEY ] || continue
    [ "$_sandbox_line" != "$_sandbox_name" ] || continue
    _sandbox_count=$((_sandbox_count + 1))
    STRIPE_API_KEY=${_sandbox_line#*=}
    # CRLF and optional matching quotes; no expansion, evaluation, or sourcing.
    STRIPE_API_KEY=${STRIPE_API_KEY%"$(printf '\r')"}
    case "$STRIPE_API_KEY" in
      \"*\") STRIPE_API_KEY=${STRIPE_API_KEY#\"}; STRIPE_API_KEY=${STRIPE_API_KEY%\"} ;;
      \'*\') STRIPE_API_KEY=${STRIPE_API_KEY#\'}; STRIPE_API_KEY=${STRIPE_API_KEY%\'} ;;
    esac
  done < "$SANDBOX_ROOT/.env"
fi
_sandbox_valid=0
case "$STRIPE_API_KEY" in rk_test_?*|sk_test_?*) _sandbox_valid=1 ;; esac
case "$STRIPE_API_KEY" in *[!A-Za-z0-9_]*) _sandbox_valid=0 ;; esac
if [ "$_sandbox_count" -ne 1 ] || [ "$_sandbox_valid" -ne 1 ]; then
  unset STRIPE_API_KEY
  printf '%s\n' 'STRIPE_API_KEY 缺失或不是测试模式密钥' >&2
  exit 3
fi
export STRIPE_API_KEY

if [ -n "${STRIPE_CLI:-}" ]; then
  STRIPE_CLI=$(command -v "$STRIPE_CLI" 2>/dev/null) || STRIPE_CLI=
elif command -v stripe >/dev/null 2>&1; then
  STRIPE_CLI=$(command -v stripe)
else
  # Git Bash sort -V compares numeric version components, unlike lexical sort.
  STRIPE_CLI=$(
    for _sandbox_cli in "${LOCALAPPDATA:-}/ledgerlab-tools/stripe-cli/"*/stripe.exe; do
      [ -f "$_sandbox_cli" ] && printf '%s\n' "$_sandbox_cli"
    done | sort -V | tail -n 1
  )
fi
# Login is always an explicit user action. No automatic login fallback.
STRIPE_CLI_USE_LOGIN=${STRIPE_CLI_USE_LOGIN:-1}
case "$STRIPE_CLI_USE_LOGIN" in 0|1) ;; *) printf '%s\n' 'Invalid STRIPE_CLI_USE_LOGIN' >&2; exit 3 ;; esac
export STRIPE_CLI STRIPE_CLI_USE_LOGIN
unset _sandbox_count _sandbox_line _sandbox_name _sandbox_valid _sandbox_cli
