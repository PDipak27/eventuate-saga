package com.saga.order.saga;

import com.saga.common.command.*;
import com.saga.common.reply.*;
import io.eventuate.tram.commands.consumer.CommandWithDestination;
import io.eventuate.tram.sagas.orchestration.SagaDefinition;
import io.eventuate.tram.sagas.simpledsl.SimpleSaga;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

import static io.eventuate.tram.commands.consumer.CommandWithDestinationBuilder.send;


/**
 * CreateOrderSaga — Eventuate Tram orchestration saga.
 *
 * SagaDefinition DSL replaces Axon @SagaEventHandler methods.
 * Each .step() maps to one local transaction.
 * .withCompensation() is called automatically by the framework in reverse order on failure.
 *
 * Transaction classification:
 *   Step 1 verifyConsumer  — compensatable (read-only, compensation = rejectOrder)
 *   Step 2 createTicket    — compensatable (compensation = rejectTicket)
 *   Step 3 authorizeCard   — PIVOT (no compensation; if passes, saga guaranteed to complete)
 *   Step 4 approveTicket   — retriable
 *   Step 5 approveOrder    — retriable
 *
 * Metrics (programmatic — AOP cannot reach private methods):
 *   saga.create_order.started            — incremented when step 1 command is dispatched
 *   saga.create_order.failed{reason}     — incremented on each failure reply (before compensation)
 *   saga.create_order.compensation       — incremented each time the RejectOrder compensation fires
 */
@Component
@Slf4j
public class CreateOrderSaga implements SimpleSaga<CreateOrderSagaData> {

    // ── Micrometer counters ───────────────────────────────────────────────────
    private final Counter sagaStartedCounter;
    private final Counter compensationCounter;
    private final Counter failedConsumerCounter;
    private final Counter failedTicketCounter;
    private final Counter failedCardCounter;

    public CreateOrderSaga(MeterRegistry registry) {
        this.sagaStartedCounter   = Counter.builder("saga.create_order.started")
                .description("Number of CreateOrder sagas initiated")
                .register(registry);
        this.compensationCounter  = Counter.builder("saga.create_order.compensation")
                .description("Number of times the RejectOrder compensation step was triggered")
                .register(registry);
        this.failedConsumerCounter = Counter.builder("saga.create_order.failed")
                .description("Number of saga failures by reason")
                .tag("reason", "consumer_verification")
                .register(registry);
        this.failedTicketCounter   = Counter.builder("saga.create_order.failed")
                .description("Number of saga failures by reason")
                .tag("reason", "ticket_creation")
                .register(registry);
        this.failedCardCounter     = Counter.builder("saga.create_order.failed")
                .description("Number of saga failures by reason")
                .tag("reason", "card_authorization")
                .register(registry);
    }

    private final SagaDefinition<CreateOrderSagaData> sagaDefinition =
        step()
            .invokeParticipant(this::makeVerifyConsumerCommand)
            .onReply(ConsumerVerifiedReply.class,           (d, r) -> log.info("[SAGA Step 1 OK] Consumer verified orderId={}", d.getOrderId()))
            .onReply(ConsumerVerificationFailedReply.class, this::handleConsumerFailed)
            .withCompensation(this::makeRejectOrderCommand)
        .step()
            .invokeParticipant(this::makeCreateTicketCommand)
            .onReply(TicketCreatedReply.class,           this::handleTicketCreated)
            .onReply(TicketCreationFailedReply.class,    this::handleTicketCreationFailed)
            .withCompensation(this::makeRejectTicketCommand)
        .step()
            .invokeParticipant(this::makeAuthorizeCreditCardCommand)
            .onReply(CardAuthorizedReply.class,           (d, r) -> log.info("[SAGA PIVOT OK] Card authorized orderId={}", d.getOrderId()))
            .onReply(CardAuthorizationFailedReply.class,  this::handleCardFailed)
        .step()
            .invokeParticipant(this::makeApproveTicketCommand)
        .step()
            .invokeParticipant(this::makeApproveOrderCommand)
        .build();

    @Override
    public SagaDefinition<CreateOrderSagaData> getSagaDefinition() {
        return sagaDefinition;
    }

    // ── Command builders ──────────────────────────────────────────────────────

    private CommandWithDestination makeVerifyConsumerCommand(CreateOrderSagaData d) {
        log.info("[SAGA Step 1] Sending VerifyConsumerCommand consumerId={}", d.getConsumerId());
        sagaStartedCounter.increment();
        return send(new VerifyConsumerCommand(d.getConsumerId(), d.getOrderId(), d.getOrderTotal()))
                .to(VerifyConsumerCommand.CHANNEL).build();
    }

    private CommandWithDestination makeRejectOrderCommand(CreateOrderSagaData d) {
        log.info("[SAGA COMPENSATE] Sending RejectOrderCommand orderId={}", d.getOrderId());
        compensationCounter.increment();
        return send(new RejectOrderCommand(d.getOrderId(), d.getRejectionReason()))
                .to(RejectOrderCommand.CHANNEL).build();
    }

    private CommandWithDestination makeCreateTicketCommand(CreateOrderSagaData d) {
        String ticketId = UUID.randomUUID().toString();
        d.setTicketId(ticketId);
        log.info("[SAGA Step 2] Sending CreateTicketCommand ticketId={}", ticketId);
        return send(new CreateTicketCommand(ticketId, d.getOrderId(), d.getConsumerId(),
                d.getLineItems(), d.getOrderTotal()))
                .to(CreateTicketCommand.CHANNEL).build();
    }

    private CommandWithDestination makeRejectTicketCommand(CreateOrderSagaData d) {
        log.info("[SAGA COMPENSATE] Sending RejectTicketCommand ticketId={}", d.getTicketId());
        return send(new RejectTicketCommand(d.getTicketId(), d.getOrderId()))
                .to(RejectTicketCommand.CHANNEL).build();
    }

    private CommandWithDestination makeAuthorizeCreditCardCommand(CreateOrderSagaData d) {
        log.info("[SAGA Step 3 PIVOT] Sending AuthorizeCreditCardCommand consumerId={}", d.getConsumerId());
        return send(new AuthorizeCreditCardCommand(d.getConsumerId(), d.getOrderId(), d.getOrderTotal()))
                .to(AuthorizeCreditCardCommand.CHANNEL).build();
    }

    private CommandWithDestination makeApproveTicketCommand(CreateOrderSagaData d) {
        log.info("[SAGA Step 4] Sending ApproveTicketCommand ticketId={}", d.getTicketId());
        return send(new ApproveTicketCommand(d.getTicketId(), d.getOrderId()))
                .to(ApproveTicketCommand.CHANNEL).build();
    }

    private CommandWithDestination makeApproveOrderCommand(CreateOrderSagaData d) {
        log.info("[SAGA Step 5] Sending ApproveOrderCommand orderId={}", d.getOrderId());
        return send(new ApproveOrderCommand(d.getOrderId()))
                .to(ApproveOrderCommand.CHANNEL).build();
    }

    // ── Reply handlers ────────────────────────────────────────────────────────

    private void handleConsumerFailed(CreateOrderSagaData d, ConsumerVerificationFailedReply r) {
        log.warn("[SAGA FAIL] Consumer verification failed orderId={}, reason={}", d.getOrderId(), r.getMessage());
        failedConsumerCounter.increment();
        d.setRejectionReason(r.getMessage());
    }

    private void handleTicketCreated(CreateOrderSagaData d, TicketCreatedReply r) {
        log.info("[SAGA Step 2 OK] Ticket created ticketId={}", d.getTicketId());
    }

    private void handleTicketCreationFailed(CreateOrderSagaData d, TicketCreationFailedReply r) {
        log.warn("[SAGA FAIL] Ticket creation failed orderId={}, reason={}", d.getOrderId(), r.getMessage());
        failedTicketCounter.increment();
        d.setRejectionReason(r.getMessage());
    }

    private void handleCardFailed(CreateOrderSagaData d, CardAuthorizationFailedReply r) {
        log.warn("[SAGA PIVOT FAIL] Card auth failed orderId={}, reason={}", d.getOrderId(), r.getMessage());
        failedCardCounter.increment();
        d.setRejectionReason(r.getMessage());
    }
}
