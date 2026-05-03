package com.saga.consumer.domain;

import com.saga.common.command.VerifyConsumerCommand;
import com.saga.common.reply.ConsumerVerificationFailedReply;
import com.saga.common.reply.ConsumerVerifiedReply;
import com.saga.consumer.repository.ConsumerRepository;
import io.eventuate.tram.commands.consumer.CommandHandlers;
import io.eventuate.tram.commands.consumer.CommandMessage;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.sagas.participant.SagaCommandHandlersBuilder;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import static io.eventuate.tram.commands.consumer.CommandHandlerReplyBuilder.withFailure;
import static io.eventuate.tram.commands.consumer.CommandHandlerReplyBuilder.withSuccess;

/**
 * Handles VerifyConsumerCommand — the compensatable read-only step in the saga.
 * Eventuate automatically deduplicates via received_messages (message_id PK).
 * The verification itself is read-only so duplicate execution produces same result.
 *
 * Metrics:
 *   @Timed("saga.command.verify_consumer") — latency histogram for the consumer check
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConsumerCommandHandlers {

    private final ConsumerRepository consumerRepository;
    //this is saga definition
    public CommandHandlers commandHandlers() {
        return SagaCommandHandlersBuilder
                .fromChannel(VerifyConsumerCommand.CHANNEL)
                .onMessage(VerifyConsumerCommand.class, this::verifyConsumer)
                .build();
    }

    @Timed(value = "saga.command.verify_consumer", description = "Time taken to handle VerifyConsumerCommand")
    @Transactional
    public Message verifyConsumer(CommandMessage<VerifyConsumerCommand> cm) {
        VerifyConsumerCommand cmd = cm.getCommand();
        log.info("Handling VerifyConsumerCommand consumerId={} orderId={}", cmd.getConsumerId(), cmd.getOrderId());

        Consumer consumer = consumerRepository.findById(cmd.getConsumerId()).orElse(null);

        if (consumer == null) {
            log.warn("Consumer {} not found", cmd.getConsumerId());
            return withFailure(new ConsumerVerificationFailedReply("Consumer not found"));
        }

        if (!consumer.isActive()) {
            log.warn("Consumer {} is not active", cmd.getConsumerId());
            return withFailure(new ConsumerVerificationFailedReply("Consumer account is not active"));
        }

        if (consumer.getCreditLimit().compareTo(cmd.getOrderTotal()) < 0) {
            log.warn("Consumer {} insufficient credit limit={} orderTotal={}",
                    cmd.getConsumerId(), consumer.getCreditLimit(), cmd.getOrderTotal());
            return withFailure(new ConsumerVerificationFailedReply(
                    "Insufficient credit limit: " + consumer.getCreditLimit()));
        }

        log.info("Consumer {} verified for orderId={}", cmd.getConsumerId(), cmd.getOrderId());
        return withSuccess(new ConsumerVerifiedReply("verified"));
    }
}
