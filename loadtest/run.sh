#!/usr/bin/env bash
# Runs loadtest/ingest.js in a k6 container on the compose network. Usage: loadtest/run.sh [RATE] [DURATION]
set -euo pipefail
cd "$(dirname "$0")/.."
ADMIN_TOKEN=$(grep -E '^HOOKRELAY_ADMIN_TOKEN=' .env | cut -d= -f2-)
docker run --rm -i --network hookrelay_hookrelay \
  -e RATE="${1:-200}" -e DURATION="${2:-60s}" -e ADMIN_TOKEN="$ADMIN_TOKEN" \
  grafana/k6:1.2.0 run --quiet - < loadtest/ingest.js
