#!/usr/bin/env bash
# Chạy toàn bộ kiểm tra hợp đồng. Dùng: tools/contracts/check_all.sh  (cần: pip install -r tools/contracts/requirements.txt)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
python3 -I -m unittest discover -s tools/contracts -p 'test_*.py'
for v in authz events openapi terminology; do python3 -I "tools/contracts/validate_$v.py"; done
# Tài liệu và schema sinh ra phải đồng bộ với bộ sinh (không sửa tay file sinh).
python3 -I tools/contracts/gen_events.py >/dev/null && python3 -I tools/contracts/gen_openapi.py >/dev/null
git diff --exit-code -- contracts/events contracts/openapi >/dev/null || { echo "ERROR: file sinh ra lệch bộ sinh; chạy lại gen_*.py và commit"; exit 1; }
echo "ALL CONTRACT CHECKS PASSED"
