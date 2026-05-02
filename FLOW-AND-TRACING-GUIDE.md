# CreateOrder Saga — Flow & Tracing Guide

## Infrastructure

| Component | Port | Role |
|---|---|---|
| PostgreSQL (local) | 5432 | orderdb, consumerdb, kitchendb, accountingdb |
| ZooKeeper | 2181 | Eventuate consumer partition coordination |
| RabbitMQ | 5672 / 15672 | Message broker (x-consistent-hash exchange) |
| Eventuate CDC | — | Polls `message` tables → publishes to RabbitMQ |

---

## Happy Path

```
POST /orders
  └─ orderdb: INSERT orders (APPROVAL_PENDING) + saga_instance + message (VerifyConsumerCommand)
       └─ CDC → RabbitMQ: consumerService
            └─ consumerdb: INSERT received_messages + message (ConsumerVerifiedReply)
                 └─ CDC → RabbitMQ: createOrderSagaReplyChannel
                      └─ orderdb: UPDATE saga_instance + INSERT message (CreateTicketCommand)
                           └─ CDC → RabbitMQ: kitchenService
                                └─ kitchendb: INSERT tickets (CREATE_PENDING) + received_messages + message (TicketCreatedReply)
                                     └─ CDC → RabbitMQ: createOrderSagaReplyChannel
                                          └─ orderdb: UPDATE saga_instance + INSERT message (AuthorizeCreditCardCommand) ★PIVOT
                                               └─ CDC → RabbitMQ: accountingService
                                                    └─ accountingdb: INSERT authorization_records + received_messages + message (CardAuthorizedReply)
                                                         └─ CDC → RabbitMQ: createOrderSagaReplyChannel
                                                              └─ orderdb: UPDATE saga_instance + INSERT message (ApproveTicketCommand + ApproveOrderCommand)
                                                                   ├─ kitchendb: UPDATE tickets (AWAITING_ACCEPTANCE)
                                                                   └─ orderdb: UPDATE orders (APPROVED), saga_instance end_state=true
```

★ = PIVOT. After `CardAuthorizedReply` the saga is guaranteed to complete.

---

## Failure Path (Card Auth Fails)

```
CardAuthorizationFailedReply received
  ├─ kitchenService ← RejectTicketCommand → kitchendb: UPDATE tickets (CREATE_REJECTED)
  └─ orderService   ← RejectOrderCommand  → orderdb: UPDATE orders (REJECTED), saga_instance compensating=true, end_state=true
```

---

## DB Tables Per Step

| Step | Handler | DB | Tables written |
|---|---|---|---|
| POST /orders | `OrderService.createOrder` | orderdb | `orders` INSERT, `saga_instance` INSERT, `message` INSERT |
| Verify consumer | `ConsumerCommandHandlers.verifyConsumer` | consumerdb | `received_messages` INSERT, `message` INSERT (reply) |
| Create ticket | `KitchenCommandHandlers.createTicket` | kitchendb | `tickets` INSERT, `received_messages` INSERT, `message` INSERT |
| Authorize card ★ | `AccountingCommandHandlers.authorizeCard` | accountingdb | `authorization_records` INSERT, `received_messages` INSERT, `message` INSERT |
| Approve ticket | `KitchenCommandHandlers.approveTicket` | kitchendb | `tickets` UPDATE, `received_messages` INSERT, `message` INSERT |
| Approve order | `OrderCommandHandlers.approve` | orderdb | `orders` UPDATE (APPROVED), `received_messages` INSERT |
| Reject ticket (comp) | `KitchenCommandHandlers.rejectTicket` | kitchendb | `tickets` UPDATE (CREATE_REJECTED) |
| Reject order (comp) | `OrderCommandHandlers.reject` | orderdb | `orders` UPDATE (REJECTED) |

---

## RabbitMQ Channels

| Channel | Carries |
|---|---|
| `consumerService` | VerifyConsumerCommand |
| `kitchenService` | CreateTicketCommand, ApproveTicketCommand, RejectTicketCommand |
| `accountingService` | AuthorizeCreditCardCommand |
| `orderService` | ApproveOrderCommand, RejectOrderCommand |
| `createOrderSagaReplyChannel` | All replies back to saga manager |

---

## Idempotency

Two layers:

1. **Framework** — Eventuate writes `(consumer_id, message_id)` to `received_messages` in the same transaction as the domain change. Duplicate delivery → handler skipped entirely.
2. **Application** — status checks in every command handler (`APPROVED`, `REJECTED`, `AWAITING_ACCEPTANCE`, `CREATE_REJECTED`) prevent redundant DB writes on retry. `authorization_records (consumerId, orderId)` prevents double-authorization at the PIVOT.

---

## Debugging a Stuck Saga

### 1. Where is the saga?
```sql
-- orderdb
SELECT saga_id, state_name, compensating, end_state, saga_data_json
FROM saga_instance WHERE saga_type = 'com.saga.order.saga.CreateOrderSaga';
```
`state_name` = current waiting step. `compensating=true` = running compensation. `end_state=true` = finished.

### 2. Is the outbox stuck?
```sql
-- Run against the DB of whichever service sent the last command
SELECT dbid, destination, published, creation_time FROM message
WHERE published = 0 ORDER BY creation_time ASC;
```
Rows older than a few seconds with `published=0` → CDC is down. Restart: `docker-compose restart eventuate-cdc`.

### 3. Did the target service receive it?
```sql
-- Run against the DB of the target service
SELECT consumer_id, message_id, creation_time FROM received_messages
ORDER BY creation_time DESC LIMIT 10;
```
If the message_id is present → handler ran, reply was sent → check `createOrderSagaReplyChannel` in RabbitMQ UI (http://localhost:15672).

### 4. Check RabbitMQ
Open http://localhost:15672 → Queues tab. Non-zero **Ready** count on any channel = service is down or not consuming.

### 5. Force-close (last resort)
```sql
-- orderdb
UPDATE saga_instance SET end_state=true, state_name='MANUAL_CLOSE'
WHERE saga_type='com.saga.order.saga.CreateOrderSaga' AND saga_id='<sagaId>';

UPDATE orders SET status='REJECTED', rejection_reason='Manual close'
WHERE id='<orderId>';
```

---

## Test Sequence

```
# Setup
POST :8082/consumers  {"consumerId":"c-1","name":"Alice","email":"a@test.com","creditLimit":500.00}
POST :8084/accounts   {"consumerId":"c-1","consumerName":"Alice","availableCredit":500.00}

# Happy path
POST :8081/orders     {"consumerId":"c-1","orderTotal":29.99,"lineItems":[{"menuItemId":"m1","name":"Pizza","quantity":1,"price":29.99}]}
GET  :8081/orders/{orderId}          → poll until status=APPROVED (~2-4s)
GET  :8083/tickets/order/{orderId}   → status=AWAITING_ACCEPTANCE

# Failure path
POST :8084/accounts   {"consumerId":"c-2","consumerName":"Bob","availableCredit":5.00}
POST :8081/orders     {"consumerId":"c-2","orderTotal":999.99,...}
GET  :8081/orders/{orderId}          → status=REJECTED
```
