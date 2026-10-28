#!/usr/bin/env bash
# Run all contract checks. Usage: tools/contracts/check_all.sh  (requires: pip install -r tools/contracts/requirements.txt)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
python3 -I -m unittest discover -s tools/contracts -p 'test_*.py'
for v in authz events openapi terminology; do python3 -I "tools/contracts/validate_$v.py"; done
# Generated docs and schemas must match the generator (do not hand-edit generated files).
python3 -I tools/contracts/gen_events.py >/dev/null && python3 -I tools/contracts/gen_openapi.py >/dev/null
git diff --exit-code -- contracts/events contracts/openapi >/dev/null || { echo "ERROR: generated files differ from the generator output; re-run gen_*.py and commit"; exit 1; }
echo "ALL CONTRACT CHECKS PASSED"
