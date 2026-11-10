# Load test

`ingest.js` (k6) does two things:

1. **Ingest phase:** `POST /v1/events` at a constant arrival rate (`RATE`/s for `DURATION`), spread
   evenly over both instances and several API keys of one application.
2. **Delivery phase** (teardown): waits until no delivery is pending, then reads every delivery
   and records `updatedAt − createdAt` for the succeeded ones (event accepted → receiver answered 2xx).

The receiver is the bundled sample receiver, with signature verification and no added latency.

```bash
cp .env.example .env && docker compose up -d --build
loadtest/run.sh 400 60s        # RATE, DURATION; k6 runs in a container on the compose network
```

Pass criteria: ingest error rate < 1 % and ingest p95 < 500 ms. Results are in the main README.
