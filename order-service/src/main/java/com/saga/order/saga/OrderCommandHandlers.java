package com.saga.order.saga;

import com.saga.common.command.ApproveOrderCommand;
import com.saga.common.command.RejectOrderCommand;
import com.saga.common.reply.OrderApprovedReply;
import com.saga.common.reply.OrderRejectedReply;
import com.saga.order.domain.Order;
import com.saga.order.domain.OrderStatus;
import com.saga.order.repository.OrderRepository;
import io.eventuate.tram.commands.consumer.CommandHandlers;
import io.eventuate.tram.commands.consumer.CommandMessage;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.sagas.participant.SagaCommandHandlersBuilder;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import static io.eventuate.tram.commands.consumer.CommandHandlerReplyBuilder.withSuccess;

/**
 * Handles saga commands targeting Order Service (retriable steps).
 * Eventuate deduplicates via received_messages table automatically.
 * Status checks provide a second idempotency layer for safe retries.
 *
 * Metrics:
 *   @Timed("saga.command.approve_order") / @Timed("saga.command.reject_order") — latency histograms
 *   saga.create_order.completed  — counts fresh approvals (saga fully succeeded)
 *   saga.create_order.compensated — counts fresh rejections (saga fully compensated)
 */
@Component
@Slf4j
public class OrderCommandHandlers {

    private final OrderRepository orderRepository;
    private final Counter sagaCompletedCounter;
    private final Counter sagaCompensatedCounter;

    public OrderCommandHandlers(OrderRepository orderRepository, MeterRegistry registry) {
        this.orderRepository    = orderRepository;
        this.sagaCompletedCounter  = Counter.builder("saga.create_order.completed")
                .description("Number of CreateOrder sagas that completed successfully")
                .register(registry);
        this.sagaCompensatedCounter = Counter.builder("saga.create_order.compensated")
                .description("Number of CreateOrder sagas that fully compensated (order rejected)")
                .register(registry);
    }

    public CommandHandlers commandHandlers() {
        return SagaCommandHandlersBuilder
                .fromChannel(ApproveOrderCommand.CHANNEL)
                .onMessage(ApproveOrderCommand.class, this::approve)
                .onMessage(RejectOrderCommand.class, this::reject)
                .build();
    }

    @Timed(value = "saga.command.approve_order", description = "Time taken to handle ApproveOrderCommand")
    @Transactional
    public Message approve(CommandMessage<ApproveOrderCommand> cm) {
        String orderId = cm.getCommand().getOrderId();
        log.info("Handling ApproveOrderCommand orderId={}", orderId);

        Order order = orderRepository.findById(orderId).orElseThrow(
                () -> new IllegalStateException("Order not found: " + orderId));

        if (order.getStatus() == OrderStatus.APPROVED) {
            log.warn("[IDEMPOTENT] Order {} already approved", orderId);
            return withSuccess(new OrderApprovedReply("already-approved"));
        }

        order.setStatus(OrderStatus.APPROVED);
        orderRepository.save(order);
        sagaCompletedCounter.increment();
        log.info("Order {} APPROVED", orderId);
        return withSuccess(new OrderApprovedReply("approved"));
    }

    @Timed(value = "saga.command.reject_order", description = "Time taken to handle RejectOrderCommand")
    @Transactional
    public Message reject(CommandMessage<RejectOrderCommand> cm) {
        String orderId = cm.getCommand().getOrderId();
        String reason  = cm.getCommand().getReason();
        log.info("Handling RejectOrderCommand orderId={} reason={}", orderId, reason);

        Order order = orderRepository.findById(orderId).orElseThrow(
                () -> new IllegalStateException("Order not found: " + orderId));

        if (order.getStatus() == OrderStatus.REJECTED) {
            log.warn("[IDEMPOTENT] Order {} already rejected", orderId);
            return withSuccess(new OrderRejectedReply("already-rejected"));
        }

        order.setStatus(OrderStatus.REJECTED);
        order.setRejectionReason(reason);
        orderRepository.save(order);
        sagaCompensatedCounter.increment();
        log.info("Order {} REJECTED reason={}", orderId, reason);
        return withSuccess(new OrderRejectedReply("rejected"));
    }
}
