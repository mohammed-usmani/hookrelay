// Ingest throughput + end-to-end delivery latency against the docker compose stack.
//   docker run --rm -i --network hookrelay_hookrelay -e RATE=200 -e ADMIN_TOKEN=... \
//     grafana/k6:1.2.0 run - < loadtest/ingest.js
// Phase 1 (scenario): constant-arrival-rate POST /v1/events for DURATION, split over both instances.
// Phase 2 (teardown): wait for every delivery to finish, then compute created→succeeded latency.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import exec from 'k6/execution';

const RATE = Number(__ENV.RATE || 200);
const DURATION = __ENV.DURATION || '60s';
const ADMIN = __ENV.ADMIN_TOKEN;
const INSTANCES = (__ENV.INSTANCES || 'http://hookrelay-1:8080,http://hookrelay-2:8080').split(',');
const RECEIVER = __ENV.RECEIVER || 'http://receiver:9000';
// Producers: the per-key inbound limit (100/s/instance) is a feature, so load comes from many keys,
// as it would from many services of one customer.
const KEYS = Number(__ENV.KEYS || Math.max(4, Math.ceil(RATE / 50)));

const deliveryLatency = new Trend('delivery_latency', true);
const undelivered = new Counter('undelivered');

export const options = {
  scenarios: {
    ingest: {
      executor: 'constant-arrival-rate',
      rate: RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: Math.min(RATE, 400), maxVUs: 1000,
    },
  },
  thresholds: {
    'http_req_failed{scenario:ingest}': ['rate<0.01'],
    'http_req_duration{scenario:ingest}': ['p(95)<500'],
  },
  setupTimeout: '2m',
  teardownTimeout: '10m',
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

const json = (token) => ({ headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } });

export function setup() {
  const api = INSTANCES[0];
  const app = http.post(`${api}/v1/admin/applications`, JSON.stringify({ name: `load-${Date.now()}` }), json(ADMIN)).json();
  const keys = [];
  for (let i = 0; i < KEYS; i++) {
    keys.push(http.post(`${api}/v1/admin/applications/${app.id}/api-keys`, JSON.stringify({ name: `k6-${i}` }), json(ADMIN)).json().key);
  }
  http.post(`${api}/v1/event-types`, JSON.stringify({ name: 'order.created' }), json(keys[0]));
  const ep = http.post(`${api}/v1/endpoints`, JSON.stringify({ url: `${RECEIVER}/webhooks` }), json(keys[0])).json();
  http.put(`${RECEIVER}/chaos/secret`, JSON.stringify({ secret: ep.secret }), json(''));
  http.put(`${RECEIVER}/chaos`, '{}', json(''));
  return { keys, endpoint: ep.id, startedAt: new Date().toISOString() };
}

export default function (data) {
  const i = exec.scenario.iterationInTest; // global, so keys and instances are used evenly
  const base = INSTANCES[i % INSTANCES.length];
  const key = data.keys[i % data.keys.length];
  const body = JSON.stringify({ eventType: 'order.created', payload: { orderId: `ord_${i}`, total: 4200, items: [{ sku: 'A-1', qty: 2 }] } });
  const res = http.post(`${base}/v1/events`, body, json(key));
  check(res, { accepted: (r) => r.status === 202 });
  if (res.status !== 202 && Math.random() < 0.002) {
    console.warn(`ingest ${res.status} via ${base}: ${String(res.body).slice(0, 200)}`);
  }
}

export function teardown(data) {
  const api = INSTANCES[0];
  let k = 0;
  // the query API is rate limited per key too: rotate keys and honour Retry-After
  const get = (url) => {
    for (;;) {
      const res = http.get(url, json(data.keys[k++ % data.keys.length]));
      if (res.status === 200) return res.json();
      sleep(Number(res.headers['Retry-After'] || 1));
    }
  };
  const unfinished = () => ['PENDING', 'FAILED_RETRYING']
    .map((s) => get(`${api}/v1/deliveries?endpointId=${data.endpoint}&status=${s}&limit=1`).data.length)
    .reduce((a, b) => a + b, 0);
  const drainStart = Date.now();
  while (unfinished() > 0 && Date.now() - drainStart < 9 * 60 * 1000) {
    sleep(1);
  }
  console.log(`drained ${(Date.now() - drainStart) / 1000}s after the ingest phase ended`);

  let cursor = null;
  let n = 0;
  do {
    const url = `${api}/v1/deliveries?endpointId=${data.endpoint}&limit=200${cursor ? `&cursor=${cursor}` : ''}`;
    const page = get(url);
    for (const d of page.data) {
      n++;
      if (d.status === 'SUCCEEDED') {
        deliveryLatency.add(Date.parse(d.updatedAt) - Date.parse(d.createdAt));
      } else {
        undelivered.add(1);
      }
    }
    cursor = page.nextCursor;
  } while (cursor);
  console.log(`deliveries checked: ${n}`);
}
