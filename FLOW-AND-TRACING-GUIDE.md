# Eventuate Tram CreateOrder Saga — Flow & Tracing Guide

---

## Architecture Overview

```
[Postman] → [Order Service] → [orderdb: orders + message + saga_instance]
                                     ↓
                             [CDC Service (orderdb)]
                                     ↓
                              [RabbitMQ: consumerService channel]
                                     ↓
                       [Consumer Service] → [consumerdb: received_messages + message]
                                                  ↓
                                          [CDC Service (consumerdb)]
                                                  ↓
                                       [RabbitMQ: createOrderSagaReplyChannel]
                                                  ↓
                            [Order Service Saga Manager] → ...continues
```

Each service writes its outbox (`message` table) and domain change atomically.
The CDC service (one per database) polls the `message` table and publishes rows to
RabbitMQ. This guarantees at-least-once delivery without distributed coordination.

---

## Happy Path — Step by Step

### Step 0 — POST /orders

**Order Service** (`OrderService.createOrder`, single `@Transactional`):

| DB Table (orderdb) | Action |
|---|---|
| `orders` | INSERT id, consumerId, status=`APPROVAL_PENDING`, orderTotal |
| `saga_instance` | INSERT saga_type=CreateOrderSaga, saga_id=uuid, state_name=verifyConsumer, saga_data=JSON |
| `message` | INSERT VerifyConsumerCommand payload → destination=`consumerService` |

CDC polls `orderdb.message` → publishes to RabbitMQ exchange → routes to `consumerService` queue.

---

### Step 1 — Verify Consumer

**Consumer Service** (`ConsumerCommandHandlers.verifyConsumer`, single `@Transactional`):

| DB Table (consumerdb) | Action |
|---|---|
| `consumers` | SELECT — read-only check (active, creditLimit) |
| `received_messages` | INSERT (consumer_id=consumerServiceCommandDispatcher, message_id=msg-uuid) |
| `message` | INSERT ConsumerVerifiedReply → destination=`createOrderSagaReplyChannel` |

CDC polls `consumerdb.message` → publishes reply to RabbitMQ → routed to Order Service.

---

### Step 2 — Create Ticket

**Order Service Saga Manager** receives ConsumerVerifiedReply:

| DB Table (orderdb) | Action |
|---|---|
| `saga_instance` | UPDATE state_name=createTicket, saga_data updated with ticketId |
| `received_messages` | INSERT for the reply message |
| `message` | INSERT CreateTicketCommand → destination=`kitchenService` |

**Kitchen Service** (`KitchenCommandHandlers.createTicket`):

| DB Table (kitchendb) | Action |
|---|---|
| `tickets` | INSERT id=ticketId, orderId, status=`CREATE_PENDING` |
| `received_messages` | INSERT (deduplication record) |
| `message` | INSERT TicketCreatedReply → destination=`createOrderSagaReplyChannel` |

---

### Step 3 — Authorize Card (PIVOT)

**Order Service Saga Manager** receives TicketCreatedReply:

| DB Table (orderdb) | Action |
|---|---|
| `saga_instance` | UPDATE state_name=authorizeCard |
| `message` | INSERT AuthorizeCreditCardCommand → destination=`accountingService` |

**Accounting Service** (`AccountingCommandHandlers.authorizeCard`):

| DB Table (accountingdb) | Action |
|---|---|
| `accounts` | SELECT — check availableCredit |
| `received_messages` | INSERT |
| `message` | INSERT CardAuthorizedReply (or CardAuthorizationFailedReply) → `createOrderSagaReplyChannel` |

If CardAuthorizedReply → saga guaranteed to complete. Proceeds to steps 4 and 5.
If CardAuthorizationFailedReply → saga triggers compensation (steps 2 and 1 in reverse).

---

### Steps 4 & 5 — Approve Ticket + Approve Order (Retriable)

**Kitchen Service** on ApproveTicketCommand:

| DB Table (kitchendb) | Action |
|---|---|
| `tickets` | UPDATE status=`AWAITING_ACCEPTANCE` |
| `received_messages` | INSERT |
| `message` | INSERT TicketApprovedReply |

**Order Service** on ApproveOrderCommand:

| DB Table (orderdb) | Action |
|---|---|
| `orders` | UPDATE status=`APPROVED` |
| `received_messages` | INSERT |
| `message` | INSERT OrderApprovedReply |
| `saga_instance` | UPDATE end_state=true |

---

## Failure Path — Card Authorization Fails

After PIVOT failure, saga calls compensation in reverse step order:

**Step 2 compensation — Reject Ticket:**

| DB Table (kitchendb) | Action |
|---|---|
| `tickets` | UPDATE status=`CREATE_REJECTED` |
| `received_messages` | INSERT |
| `message` | INSERT TicketRejectedReply |

**Step 1 compensation — Reject Order:**

| DB Table (orderdb) | Action |
|---|---|
| `orders` | UPDATE status=`REJECTED`, rejectionReason |
| `received_messages` | INSERT |
| `saga_instance` | UPDATE compensating=true, end_state=true |

---

## Idempotency — How It Works

Eventuate writes `(consumer_id, message_id)` into `received_messages` in the **same
transaction** as the domain change and the reply message write. On duplicate delivery
(CDC retry, broker retry), Eventuate checks `received_messages` first. If the record
exists, the entire handler is skipped. This is framework-managed — no application code
needed. Additional status checks in command handlers provide a second safety layer for
retriable steps (e.g., ApproveOrder skips if already APPROVED).

---

## Tracing a Stuck Saga (Debugging Playbook)

### The saga has been in APPROVAL_PENDING for too long

**Step 1 — Check order status**
```sql
-- orderdb
SELECT id, consumer_id, status, rejection_reason, created_at, updated_at
FROM orders WHERE id = '<orderId>';
```
If status is still APPROVAL_PENDING, the saga has not yet completed.

**Step 2 — Find the saga instance and current step**
```sql
-- orderdb
SELECT saga_id, state_name, compensating, end_state, saga_data
FROM saga_instance WHERE saga_type = 'com.saga.order.saga.CreateOrderSaga';
```
`state_name` tells you exactly which step the saga is waiting on.
`compensating = true` means it is currently running compensation.
`end_state = true` means it finished (check orders table for final status).

**Step 3 — Check the outbox for stuck messages**
```sql
-- Run against the DB of the service that SENT the last command
-- (e.g. orderdb if state_name = 'verifyConsumer')
SELECT id, destination, payload, published, creation_time
FROM message
WHERE published = 0
ORDER BY creation_time ASC;
```
`published = 0` means CDC has not yet picked up this message.
If rows are old (creation_time > a few seconds ago), the CDC service is down or stuck.
Fix: restart the CDC container for that database.

**Step 4 — Check the deduplication table at the target service**
```sql
-- Run against the DB of the service that should RECEIVE the command
-- e.g. consumerdb to check if VerifyConsumerCommand was handled
SELECT consumer_id, message_id, creation_time
FROM received_messages
ORDER BY creation_time DESC LIMIT 20;
```
If the message_id of the stuck command appears here, the handler ran and the reply was
sent. The problem is in the reply delivery back to Order Service — check orderdb.message
for the reply row and whether its CDC has published it.

**Step 5 — Check that the reply reached Order Service**
```sql
-- orderdb
SELECT consumer_id, message_id, creation_time
FROM received_messages
ORDER BY creation_time DESC LIMIT 20;
```
If the reply message_id is here, Order Service received it and advanced the saga.
If not, the reply message is either still in the target service's outbox (Step 3 above)
or in RabbitMQ (check Management UI at localhost:15672, look for undelivered messages
on the `createOrderSagaReplyChannel` queue).

**Step 6 — Check RabbitMQ queue depths**

Open `http://localhost:15672` → Queues tab.
Each channel (consumerService, kitchenService, accountingService, orderService,
createOrderSagaReplyChannel) appears as a queue. A non-zero "Ready" count means messages
are waiting but not consumed. This means the target service's CommandDispatcher is not
running — either the service is down or its Spring context failed to start.

**Step 7 — Force-complete a stuck saga (last resort)**
```sql
-- orderdb — manually mark saga as ended if compensation is complete but saga_instance
-- was not updated (e.g. due to a crash after the domain change but before saga update)
UPDATE saga_instance
SET end_state = true, state_name = 'MANUAL_CLOSE'
WHERE saga_type = 'com.saga.order.saga.CreateOrderSaga'
  AND saga_id = '<sagaId>';

-- Then update the order to its correct final state
UPDATE orders SET status = 'REJECTED', rejection_reason = 'Manual close after investigation'
WHERE id = '<orderId>';
```

---

## Postman Test Sequence

```
1. POST :8082/consumers
   {"consumerId":"c-1","name":"Alice","email":"a@test.com","creditLimit":500.00}

2. POST :8084/accounts
   {"consumerId":"c-1","consumerName":"Alice","availableCredit":500.00}

3. POST :8081/orders
   {"consumerId":"c-1","orderTotal":29.99,
    "lineItems":[{"menuItemId":"m1","name":"Pizza","quantity":1,"price":29.99}]}
   → returns {orderId, status:"APPROVAL_PENDING"}

4. GET :8081/orders/{orderId}
   → poll until status = "APPROVED" (typically 2–4 seconds with CDC polling at 500ms)

5. GET :8083/tickets/order/{orderId}
   → ticket status should be AWAITING_ACCEPTANCE

Failure test:
   POST /accounts with availableCredit: 5.00
   POST /orders  with orderTotal: 999.99
   → order ends as REJECTED
```
