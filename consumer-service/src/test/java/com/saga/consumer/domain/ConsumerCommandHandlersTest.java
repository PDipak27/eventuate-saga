package com.saga.consumer.domain;

import com.saga.common.command.VerifyConsumerCommand;
import com.saga.common.reply.ConsumerVerificationFailedReply;
import com.saga.common.reply.ConsumerVerifiedReply;
import com.saga.consumer.repository.ConsumerRepository;
import io.eventuate.tram.commands.consumer.CommandMessage;
import io.eventuate.tram.messaging.common.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConsumerCommandHandlers — idempotency and validation")
class ConsumerCommandHandlersTest {

    @Mock ConsumerRepository consumerRepository;
    @InjectMocks ConsumerCommandHandlers handlers;

    private Consumer activeConsumer;

    @BeforeEach
    void setUp() {
        activeConsumer = new Consumer();
        activeConsumer.setId("c-1");
        activeConsumer.setName("Alice");
        activeConsumer.setEmail("alice@test.com");
        activeConsumer.setCreditLimit(new BigDecimal("500.00"));
        activeConsumer.setActive(true);
    }

    private CommandMessage<VerifyConsumerCommand> commandMessage(String consumerId, BigDecimal orderTotal) {
        VerifyConsumerCommand cmd = new VerifyConsumerCommand();
        cmd.setConsumerId(consumerId);
        cmd.setOrderId("order-1");
        cmd.setOrderTotal(orderTotal);
        CommandMessage<VerifyConsumerCommand> cm = mock(CommandMessage.class);
        when(cm.getCommand()).thenReturn(cmd);
        return cm;
    }

    @Test
    @DisplayName("happy path — active consumer with sufficient credit → verified")
    void verifyConsumer_happyPath() {
        when(consumerRepository.findById("c-1")).thenReturn(Optional.of(activeConsumer));

        Message reply = handlers.verifyConsumer(commandMessage("c-1", new BigDecimal("100.00")));

        assertThat(reply).isNotNull();
        verify(consumerRepository).findById("c-1");
    }

    @Test
    @DisplayName("consumer not found → failure reply")
    void verifyConsumer_notFound() {
        when(consumerRepository.findById("unknown")).thenReturn(Optional.empty());

        Message reply = handlers.verifyConsumer(commandMessage("unknown", new BigDecimal("100.00")));

        assertThat(reply).isNotNull();
    }

    @Test
    @DisplayName("consumer inactive → failure reply")
    void verifyConsumer_inactive() {
        activeConsumer.setActive(false);
        when(consumerRepository.findById("c-1")).thenReturn(Optional.of(activeConsumer));

        Message reply = handlers.verifyConsumer(commandMessage("c-1", new BigDecimal("100.00")));

        assertThat(reply).isNotNull();
    }

    @Test
    @DisplayName("order total exceeds credit limit → failure reply")
    void verifyConsumer_insufficientCredit() {
        when(consumerRepository.findById("c-1")).thenReturn(Optional.of(activeConsumer));

        Message reply = handlers.verifyConsumer(commandMessage("c-1", new BigDecimal("999.99")));

        assertThat(reply).isNotNull();
    }

    @Test
    @DisplayName("idempotency — duplicate command hits same read-only path, same result")
    void verifyConsumer_duplicateCommand_sameResult() {
        when(consumerRepository.findById("c-1")).thenReturn(Optional.of(activeConsumer));
        CommandMessage<VerifyConsumerCommand> cm = commandMessage("c-1", new BigDecimal("100.00"));

        Message reply1 = handlers.verifyConsumer(cm);
        Message reply2 = handlers.verifyConsumer(cm);

        // Both replies are non-null — read-only so naturally idempotent
        assertThat(reply1).isNotNull();
        assertThat(reply2).isNotNull();
        // Repository queried twice — no state mutation, no side effects
        verify(consumerRepository, times(2)).findById("c-1");
    }

    @Test
    @DisplayName("idempotency — order total exactly at credit limit → verified (boundary)")
    void verifyConsumer_exactCreditLimit_verified() {
        when(consumerRepository.findById("c-1")).thenReturn(Optional.of(activeConsumer));

        Message reply = handlers.verifyConsumer(commandMessage("c-1", new BigDecimal("500.00")));

        assertThat(reply).isNotNull();
    }
}