/**
 * k6 load test — eventuate-saga CreateOrderSaga
 *
 * Run:
 *   k6 run k6/load-test.js
 *
 * Tune VUs / duration without editing the file:
 *   k6 run --vus 20 --duration 60s k6/load-test.js
 *
 * What it does:
 *   setup()   — seeds 20 consumers via consumer-service (10 solvent, 10 broke).
 *               Runs once before load; IDs are passed to every VU.
 *   default() — fires POST /orders in a loop; 70 % solvent (saga succeeds),
 *               30 % broke (saga compensates). Both paths exercise the full
 *               5-step saga including compensation.
 *   poll()    — each VU optionally polls GET /orders/{id} up to 10 times
 *               (500 ms apart) to observe saga completion in Grafana.
 *
 * Services must be running locally:
 *   order-service    → http://localhost:8081
 *   consumer-service → http://localhost:8082
 */

import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";

// ── Custom metrics ────────────────────────────────────────────────────────────
const sagaApproved   = new Counter("saga_approved");
const sagaRejected   = new Counter("saga_rejected");
const sagaPending    = new Counter("saga_pending");       // still APPROVAL_PENDING after poll
const sagaCompletionTime = new Trend("saga_completion_ms", true);
const orderErrors    = new Rate("order_errors");

// ── Config ────────────────────────────────────────────────────────────────────
const ORDER_SVC    = __ENV.ORDER_SVC    || "http://localhost:8081";
const CONSUMER_SVC = __ENV.CONSUMER_SVC || "http://localhost:8082";

// How long to poll for saga completion per order (ms) and interval between polls
const POLL_TIMEOUT_MS  = 5_000;
const POLL_INTERVAL_MS = 500;

// ── Load profile ──────────────────────────────────────────────────────────────
export const options = {
  stages: [
    { duration: "15s", target: 5  },   // warm-up
    { duration: "30s", target: 30 },   // ramp-up
    { duration: "60s", target: 30 },   // sustained
    { duration: "15s", target: 0  },   // ramp-down
  ],
  thresholds: {
    http_req_duration:            ["p(95)<3000"],   // 95th-pct POST under 3 s
    "saga_completion_ms":         ["p(95)<5000"],   // saga finishes within 5 s
    order_errors:                 ["rate<0.05"],    // fewer than 5 % HTTP errors
    http_req_failed:              ["rate<0.05"],
  },
};

// ── Setup: seed consumers once ────────────────────────────────────────────────
export function setup() {
  const headers = { "Content-Type": "application/json" };
  const solventIds = [];
  const brokeIds   = [];

  // 10 solvent consumers — creditLimit well above order total so saga succeeds
  for (let i = 1; i <= 10; i++) {
    const id = `load-test-solvent-${i}`;
    const body = JSON.stringify({
      consumerId:  id,
      name:        `Solvent User ${i}`,
      email:       `solvent${i}@test.local`,
      creditLimit: 10000.00,
    });
    const res = http.post(`${CONSUMER_SVC}/consumers`, body, { headers });
    // 200 = created, 409 = already exists from a previous run — both are fine
    if (res.status !== 200 && res.status !== 409) {
      console.error(`Failed to seed solvent consumer ${id}: ${res.status} ${res.body}`);
    }
    solventIds.push(id);
  }

  // 10 broke consumers — creditLimit of $1 so card authorisation always fails
  for (let i = 1; i <= 10; i++) {
    const id = `load-test-broke-${i}`;
    const body = JSON.stringify({
      consumerId:  id,
      name:        `Broke User ${i}`,
      email:       `broke${i}@test.local`,
      creditLimit: 1.00,
    });
    const res = http.post(`${CONSUMER_SVC}/consumers`, body, { headers });
    if (res.status !== 200 && res.status !== 409) {
      console.error(`Failed to seed broke consumer ${id}: ${res.status} ${res.body}`);
    }
    brokeIds.push(id);
  }

  console.log(`Seeded ${solventIds.length} solvent + ${brokeIds.length} broke consumers`);
  return { solventIds, brokeIds };
}

// ── Default: place orders ─────────────────────────────────────────────────────
export default function (data) {
  const { solventIds, brokeIds } = data;

  // 70 % solvent → saga APPROVED, 30 % broke → saga REJECTED (compensation)
  const useSolvent  = Math.random() < 0.7;
  const pool        = useSolvent ? solventIds : brokeIds;
  const consumerId  = pool[Math.floor(Math.random() * pool.length)];

  // Order total is $150 — above $1 limit but well under $10,000
  const orderBody = JSON.stringify({
    consumerId,
    lineItems: [
      { name: "Margherita Pizza", quantity: 2, price: 50.00 },
      { name: "Garlic Bread",     quantity: 1, price: 50.00 },
    ],
    orderTotal: 150.00,
  });

  const headers   = { "Content-Type": "application/json" };
  const createRes = http.post(`${ORDER_SVC}/orders`, orderBody, { headers, tags: { name: "POST /orders" } });

  const createOk = check(createRes, {
    "order accepted (202)": (r) => r.status === 202,
    "response has orderId":  (r) => {
      try { return !!JSON.parse(r.body).orderId; } catch { return false; }
    },
  });

  orderErrors.add(!createOk);

  if (!createOk) {
    console.warn(`Order creation failed: ${createRes.status} — ${createRes.body}`);
    sleep(0.5);
    return;
  }

  const orderId   = JSON.parse(createRes.body).orderId;
  const startedAt = Date.now();

  // ── Poll for saga completion ──────────────────────────────────────────────
  let finalStatus = "APPROVAL_PENDING";
  for (let attempt = 0; attempt < POLL_TIMEOUT_MS / POLL_INTERVAL_MS; attempt++) {
    sleep(POLL_INTERVAL_MS / 1000);

    const pollRes = http.get(`${ORDER_SVC}/orders/${orderId}`, { tags: { name: "GET /orders/:id" } });
    if (pollRes.status !== 200) break;

    try {
      const order = JSON.parse(pollRes.body);
      if (order.status === "APPROVED" || order.status === "REJECTED") {
        finalStatus = order.status;
        break;
      }
    } catch (_) { break; }
  }

  const elapsed = Date.now() - startedAt;

  // Record outcome
  if (finalStatus === "APPROVED") {
    sagaApproved.add(1);
    sagaCompletionTime.add(elapsed);
  } else if (finalStatus === "REJECTED") {
    sagaRejected.add(1);
    sagaCompletionTime.add(elapsed);
  } else {
    sagaPending.add(1);   // saga took longer than POLL_TIMEOUT_MS — not an error
  }

  check(null, {
    "saga reached terminal state": () => finalStatus !== "APPROVAL_PENDING",
  });
}
