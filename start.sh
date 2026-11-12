#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
if [ ! -x .venv/bin/python ]; then
  python3 -m venv --system-site-packages .venv
  .venv/bin/python -m pip install -r desktop/requirements.txt
fi
export PYTHONPATH="$PWD/desktop"
exec .venv/bin/python -m zapas "$@"
