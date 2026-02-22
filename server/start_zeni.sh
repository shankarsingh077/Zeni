#!/bin/bash
set -e
cd "$(dirname "$0")"
MODE="${1:-dev}"
echo "🚀 Zeni Voice AI Server (Groq + Google)"

# Kill any existing server on port 8765
lsof -ti :8765 | xargs kill -9 2>/dev/null || true
sleep 1

export PYTHONPATH="${PYTHONPATH}:$(pwd)"
export GOOGLE_APPLICATION_CREDENTIALS="${GOOGLE_APPLICATION_CREDENTIALS:-$(pwd)/../ZENI-TTS-KEY.json}"

if [ "$MODE" == "prod" ]; then
    uvicorn server:app --host 0.0.0.0 --port 8765 --loop uvloop --workers 4 --log-level warning
else
    uvicorn server:app --host 0.0.0.0 --port 8765 --loop uvloop --reload --log-level info
fi
