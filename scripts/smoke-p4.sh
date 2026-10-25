#!/bin/sh
set -eu
# Keep the complete P3 regression, then check P4's absolute sample balances in
# another empty database. Each suite owns and removes its own Compose project.
sh "$(dirname "$0")/smoke-p3.sh"
exec sh "$(dirname "$0")/smoke-common.sh" p4
