package com.saga.accounting.domain;

import com.saga.common.command.AuthorizeCreditCardCommand;
import com.saga.common.reply.CardAuthorizationFailedReply;
import com.saga.common.reply.CardAuthorizedReply;
import com.saga.accounting.repository.AccountRepository;
import com.saga.accounting.repository.AuthorizationRecordRepository;

import io.eventuate.tram.commands.consumer.CommandHandlers;
import io.eventuate.tram.commands.consumer.CommandMessage;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.sagas.participant.SagaCommandHandlersBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import static io.eventuate.tram.commands.consumer.CommandHandlerReplyBuilder.withFailure;
import static io.eventuate.tram.commands.consumer.CommandHandlerReplyBuilder.withSuccess;

/**
 * Handles AuthorizeCreditCardCommand — the PIVOT transaction.
 *
 * Idempotency:
 *   Eventuate deduplicates by message_id in received_messages automatically.
 *   The authorization check is read-only (doesn't deduct credit in this demo),
 *   so it is naturally idempotent: same inputs always produce same reply.
 *   In a production system that deducts credit, add an authorization_records table
 *   keyed on orderId to prevent double-deduction on retry.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AccountingCommandHandlers {

    private final AccountRepository accountRepository;
    private final AuthorizationRecordRepository authorizationRecordRepository;

    public CommandHandlers commandHandlers() {
        return SagaCommandHandlersBuilder
                .fromChannel(AuthorizeCreditCardCommand.CHANNEL)
                .onMessage(AuthorizeCreditCardCommand.class, this::authorizeCard)
                .build();
    }

    @Transactional
    public Message authorizeCard(CommandMessage<AuthorizeCreditCardCommand> cm) {
        AuthorizeCreditCardCommand cmd = cm.getCommand();
        log.info("[PIVOT] Handling AuthorizeCreditCardCommand consumerId={} orderId={} amount={}",
                cmd.getConsumerId(), cmd.getOrderId(), cmd.getOrderTotal());
 
        // Idempotency layer 2 — check if already processed
        return authorizationRecordRepository
                .findByConsumerIdAndOrderId(cmd.getConsumerId(), cmd.getOrderId())
                .map(existing -> {
                    log.warn("[IDEMPOTENT] Authorization already recorded for orderId={} authorized={}",
                            cmd.getOrderId(), existing.isAuthorized());
                    return existing.isAuthorized()
                            ? withSuccess(new CardAuthorizedReply("already-authorized"))
                            : withFailure(new CardAuthorizationFailedReply(existing.getFailureReason()));
                })
                .orElseGet(() -> performAuthorization(cmd));
    }
 
    private Message performAuthorization(AuthorizeCreditCardCommand cmd) {
        Account account = accountRepository.findById(cmd.getConsumerId()).orElse(null);
 
        AuthorizationRecord record = new AuthorizationRecord();
        record.setConsumerId(cmd.getConsumerId());
        record.setOrderId(cmd.getOrderId());
        record.setAmount(cmd.getOrderTotal());
 
        if (account == null) {
            log.error("No account found for consumerId={}", cmd.getConsumerId());
            record.setAuthorized(false);
            record.setFailureReason("No account found for consumer");
            authorizationRecordRepository.save(record);
            return withFailure(new CardAuthorizationFailedReply("No account found for consumer"));
        }
 
        if (account.getAvailableCredit().compareTo(cmd.getOrderTotal()) < 0) {
            String reason = String.format("Insufficient credit. Available: %s, Required: %s",
                    account.getAvailableCredit(), cmd.getOrderTotal());
            log.warn("[PIVOT FAIL] {} orderId={}", reason, cmd.getOrderId());
            record.setAuthorized(false);
            record.setFailureReason(reason);
            authorizationRecordRepository.save(record);
            return withFailure(new CardAuthorizationFailedReply(reason));
        }
 
        record.setAuthorized(true);
        authorizationRecordRepository.save(record);
        log.info("[PIVOT OK] Card authorized consumerId={} orderId={}", cmd.getConsumerId(), cmd.getOrderId());
        return withSuccess(new CardAuthorizedReply("authorized"));
    }
}
