#!/usr/bin/env bash
# Backwards-compatible entry point. The supported implementation lives in scripts/.
set -euo pipefail
exec "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/scripts/bootstrap-local.sh" "$@"
