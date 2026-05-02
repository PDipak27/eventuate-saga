package com.saga.accounting.domain;

import com.saga.accounting.repository.AccountRepository;
import com.saga.accounting.repository.AuthorizationRecordRepository;
import com.saga.common.command.AuthorizeCreditCardCommand;
import io.eventuate.tram.commands.consumer.CommandMessage;
import io.eventuate.tram.messaging.common.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountingCommandHandlers — idempotency via authorization_records")
class AccountingCommandHandlersTest {

    @Mock AccountRepository accountRepository;
    @Mock AuthorizationRecordRepository authorizationRecordRepository;
    @InjectMocks AccountingCommandHandlers handlers;

    private Account account;

    @BeforeEach
    void setUp() {
        account = new Account();
        account.setConsumerId("c-1");
        account.setAvailableCredit(new BigDecimal("500.00"));
    }

    private CommandMessage<AuthorizeCreditCardCommand> commandMessage(String orderId, BigDecimal amount) {
        AuthorizeCreditCardCommand cmd = new AuthorizeCreditCardCommand();
        cmd.setConsumerId("c-1");
        cmd.setOrderId(orderId);
        cmd.setOrderTotal(amount);
        CommandMessage<AuthorizeCreditCardCommand> cm = mock(CommandMessage.class);
        when(cm.getCommand()).thenReturn(cmd);
        return cm;
    }

    @Test
    @DisplayName("happy path — sufficient credit, no prior record → authorized, record saved")
    void authorizeCard_happyPath() {
        when(authorizationRecordRepository.findByConsumerIdAndOrderId("c-1", "o-1"))
                .thenReturn(Optional.empty());
        when(accountRepository.findById("c-1")).thenReturn(Optional.of(account));

        handlers.authorizeCard(commandMessage("o-1", new BigDecimal("100.00")));

        ArgumentCaptor<AuthorizationRecord> captor = ArgumentCaptor.forClass(AuthorizationRecord.class);
        verify(authorizationRecordRepository).save(captor.capture());
        assertThat(captor.getValue().isAuthorized()).isTrue();
    }

    @Test
    @DisplayName("insufficient credit → failed, record saved with failure reason")
    void authorizeCard_insufficientCredit_recordSaved() {
        when(authorizationRecordRepository.findByConsumerIdAndOrderId("c-1", "o-1"))
                .thenReturn(Optional.empty());
        when(accountRepository.findById("c-1")).thenReturn(Optional.of(account));

        handlers.authorizeCard(commandMessage("o-1", new BigDecimal("999.99")));

        ArgumentCaptor<AuthorizationRecord> captor = ArgumentCaptor.forClass(AuthorizationRecord.class);
        verify(authorizationRecordRepository).save(captor.capture());
        assertThat(captor.getValue().isAuthorized()).isFalse();
        assertThat(captor.getValue().getFailureReason()).isNotBlank();
    }

    @Test
    @DisplayName("account not found → failed, record saved")
    void authorizeCard_noAccount_recordSaved() {
        when(authorizationRecordRepository.findByConsumerIdAndOrderId("c-1", "o-1"))
                .thenReturn(Optional.empty());
        when(accountRepository.findById("c-1")).thenReturn(Optional.empty());

        handlers.authorizeCard(commandMessage("o-1", new BigDecimal("100.00")));

        ArgumentCaptor<AuthorizationRecord> captor = ArgumentCaptor.forClass(AuthorizationRecord.class);
        verify(authorizationRecordRepository).save(captor.capture());
        assertThat(captor.getValue().isAuthorized()).isFalse();
    }

    @Test
    @DisplayName("idempotency — prior authorized record → success reply, NO second DB write")
    void authorizeCard_alreadyAuthorized_noDoubleWrite() {
        AuthorizationRecord existing = new AuthorizationRecord();
        existing.setConsumerId("c-1");
        existing.setOrderId("o-1");
        existing.setAuthorized(true);
        when(authorizationRecordRepository.findByConsumerIdAndOrderId("c-1", "o-1"))
                .thenReturn(Optional.of(existing));

        Message reply = handlers.authorizeCard(commandMessage("o-1", new BigDecimal("100.00")));

        assertThat(reply).isNotNull();
        // No account lookup, no second save — pure idempotency short-circuit
        verifyNoInteractions(accountRepository);
        verify(authorizationRecordRepository, never()).save(any());
    }

    @Test
    @DisplayName("idempotency — prior FAILED record → failure reply, NO second DB write")
    void authorizeCard_alreadyFailed_noDoubleWrite() {
        AuthorizationRecord existing = new AuthorizationRecord();
        existing.setConsumerId("c-1");
        existing.setOrderId("o-1");
        existing.setAuthorized(false);
        existing.setFailureReason("Insufficient credit. Available: 5.00, Required: 999.99");
        when(authorizationRecordRepository.findByConsumerIdAndOrderId("c-1", "o-1"))
                .thenReturn(Optional.of(existing));

        Message reply = handlers.authorizeCard(commandMessage("o-1", new BigDecimal("999.99")));

        assertThat(reply).isNotNull();
        verifyNoInteractions(accountRepository);
        verify(authorizationRecordRepository, never()).save(any());
    }

    @Test
    @DisplayName("idempotency — duplicate command twice: first writes, second short-circuits")
    void authorizeCard_duplicateCommand_firstWritesSecondShortCircuits() {
        AuthorizationRecord savedRecord = new AuthorizationRecord();
        savedRecord.setConsumerId("c-1");
        savedRecord.setOrderId("o-1");
        savedRecord.setAuthorized(true);

        when(authorizationRecordRepository.findByConsumerIdAndOrderId("c-1", "o-1"))
                .thenReturn(Optional.empty())         // first call
                .thenReturn(Optional.of(savedRecord)); // second call (retry)
        when(accountRepository.findById("c-1")).thenReturn(Optional.of(account));

        handlers.authorizeCard(commandMessage("o-1", new BigDecimal("100.00")));
        handlers.authorizeCard(commandMessage("o-1", new BigDecimal("100.00")));

        verify(accountRepository, times(1)).findById("c-1");
        verify(authorizationRecordRepository, times(1)).save(any());
    }
}