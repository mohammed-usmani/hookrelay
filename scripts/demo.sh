#!/usr/bin/env bash
# End-to-end demo against `docker compose up`: deliver, break the receiver, watch retries and the
# dead-letter path, fix it, bulk-replay. Needs curl and jq.
set -euo pipefail
cd "$(dirname "$0")/.."

API=${API:-http://localhost:8080}
RECEIVER=${RECEIVER:-http://localhost:9000}
RECEIVER_INTERNAL=${RECEIVER_INTERNAL:-http://receiver:9000}
ADMIN_TOKEN=${HOOKRELAY_ADMIN_TOKEN:-$(grep -E '^HOOKRELAY_ADMIN_TOKEN=' .env | cut -d= -f2-)}

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
admin() { curl -fsS -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' "$@"; }
api() { curl -fsS -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' "$@"; }
send() { api -X POST "$API/v1/events" -d "{\"eventType\":\"$1\",\"payload\":$2}" | jq -r .id; }
count() { api "$API/v1/deliveries?endpointId=$ENDPOINT&status=$1&limit=200" | jq '.data | length'; }

step "Waiting for HookRelay"
for _ in $(seq 60); do curl -fs "$API/v3/api-docs" > /dev/null && break; sleep 2; done

step "Create an application and an API key"
APP=$(admin -X POST "$API/v1/admin/applications" -d '{"name":"Demo Shop"}' | jq -r .id)
KEY=$(admin -X POST "$API/v1/admin/applications/$APP/api-keys" -d '{"name":"demo"}' | jq -r .key)
echo "app=$APP key=${KEY:0:16}…"

step "Register event types"
api -X POST "$API/v1/event-types" -d '{"name":"order.created","description":"A customer placed an order"}' > /dev/null
api -X POST "$API/v1/event-types" -d '{"name":"order.refunded"}' > /dev/null
echo "order.created, order.refunded"

step "Add an endpoint pointing at the sample receiver, and give the receiver its secret"
EP=$(api -X POST "$API/v1/endpoints" -d "{\"url\":\"$RECEIVER_INTERNAL/webhooks\",\"description\":\"demo receiver\"}")
ENDPOINT=$(jq -r .id <<< "$EP")
curl -fsS -X PUT "$RECEIVER/chaos/secret" -H 'Content-Type: application/json' \
  -d "{\"secret\":\"$(jq -r .secret <<< "$EP")\"}"
curl -fsS -X PUT "$RECEIVER/chaos" -H 'Content-Type: application/json' -d '{}' > /dev/null
echo "endpoint=$ENDPOINT"

step "Send 5 events"
for i in 1 2 3 4 5; do send order.created "{\"orderId\":\"ord_$i\",\"total\":$((i * 1000))}"; done
sleep 3
curl -fsS "$RECEIVER/received" | jq -c '.[:5][] | {webhookId, eventType, verified, duplicate}'

step "Break the receiver (every request → 503) and send 3 more"
START=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -fsS -X PUT "$RECEIVER/chaos" -H 'Content-Type: application/json' -d '{"forceStatus":503}' > /dev/null
for i in 6 7 8; do send order.created "{\"orderId\":\"ord_$i\"}"; done

step "Watch the retries (demo schedule 1s, 5s, 5s, 30s, 30s, 30s, 30s; the circuit breaker opens too)"
for _ in $(seq 120); do
  api "$API/v1/deliveries?endpointId=$ENDPOINT&limit=3" \
    | jq -r '.data[] | "  \(.id[0:13])… \(.status)  attempts=\(.attemptCount)  \(.lastError // "")"'
  [ "$(count DEAD)" -ge 3 ] && break
  echo "  …"; sleep 5
done

step "Dead deliveries"
api "$API/v1/deliveries?endpointId=$ENDPOINT&status=DEAD" | jq -c '.data[] | {id, deadReason, attemptCount}'
DEAD_ONE=$(api "$API/v1/deliveries?endpointId=$ENDPOINT&status=DEAD&limit=1" | jq -r '.data[0].id')
echo "attempt history of $DEAD_ONE:"
api "$API/v1/deliveries/$DEAD_ONE/attempts" | jq -r '.data[] | "  #\(.attemptNo) \(.outcome) \(.responseStatus // "-") \(.durationMs)ms"'

step "Fix the receiver and bulk-replay everything that died since $START"
curl -fsS -X PUT "$RECEIVER/chaos" -H 'Content-Type: application/json' -d '{}' > /dev/null
api -X POST "$API/v1/endpoints/$ENDPOINT/resume" > /dev/null # resets the circuit breaker
api -X POST "$API/v1/endpoints/$ENDPOINT/replay" -d "{\"since\":\"$START\"}" | jq -c .
for _ in $(seq 60); do [ "$(count SUCCEEDED)" -ge 8 ] && break; sleep 2; done

step "Result"
api "$API/v1/deliveries?endpointId=$ENDPOINT" | jq -r '.data[] | "  \(.eventId[0:13])… \(.status) attempts=\(.attemptCount)"'
api "$API/v1/endpoints/$ENDPOINT/stats" | jq -c '{attempts, successes, successRate, p50Ms, p95Ms, circuitState}'
[ "$(count SUCCEEDED)" -ge 8 ] || { echo "not everything was delivered" >&2; exit 1; }

printf '\nGrafana: http://localhost:3000   RabbitMQ: http://localhost:15672   API docs: %s/swagger-ui.html\n' "$API"
