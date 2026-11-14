#!/usr/bin/env bash
# Sobe o Roku Painel em http://localhost:8000 (ou $PORT).
set -euo pipefail

cd "$(dirname "$0")"
PORT="${PORT:-8000}"
VENV="${VENV:-.venv}"

if [ ! -d "$VENV" ]; then
  echo "› criando ambiente virtual em $VENV"
  python3 -m venv "$VENV"
fi

# shellcheck disable=SC1091
source "$VENV/bin/activate"
python -m pip install --quiet --upgrade pip
python -m pip install --quiet -r requirements.txt

echo "› Roku Painel em http://0.0.0.0:$PORT (Ctrl+C para parar)"
exec python -m uvicorn server.main:app --host 0.0.0.0 --port "$PORT"
