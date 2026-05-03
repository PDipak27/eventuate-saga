package com.saga.kitchen.domain;

import com.saga.common.command.ApproveTicketCommand;
import com.saga.common.command.CreateTicketCommand;
import com.saga.common.command.RejectTicketCommand;
import com.saga.common.reply.*;
import com.saga.kitchen.repository.TicketRepository;
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
 * Handles all kitchen commands from the saga.
 *
 * Idempotency:
 *   Eventuate deduplicates by message_id in received_messages (same transaction).
 *   CreateTicket: existsById check prevents duplicate INSERT on retry.
 *   ApproveTicket / RejectTicket: status checks prevent redundant updates.
 *
 * Metrics:
 *   @Timed("saga.command.create_ticket")  — latency histogram for ticket creation
 *   @Timed("saga.command.approve_ticket") — latency histogram for ticket approval
 *   @Timed("saga.command.reject_ticket")  — latency histogram for compensation step
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KitchenCommandHandlers {

    private final TicketRepository ticketRepository;

    public CommandHandlers commandHandlers() {
        return SagaCommandHandlersBuilder
                .fromChannel(CreateTicketCommand.CHANNEL)
                .onMessage(CreateTicketCommand.class,  this::createTicket)
                .onMessage(ApproveTicketCommand.class, this::approveTicket)
                .onMessage(RejectTicketCommand.class,  this::rejectTicket)
                .build();
    }

    @Timed(value = "saga.command.create_ticket", description = "Time taken to handle CreateTicketCommand")
    @Transactional
    public Message createTicket(CommandMessage<CreateTicketCommand> cm) {
        CreateTicketCommand cmd = cm.getCommand();
        log.info("Handling CreateTicketCommand ticketId={} orderId={}", cmd.getTicketId(), cmd.getOrderId());

        // Idempotency guard — compensatable transaction
        if (ticketRepository.existsById(cmd.getTicketId())) {
            log.warn("[IDEMPOTENT] Ticket {} already exists", cmd.getTicketId());
            return withSuccess(new TicketCreatedReply("already-exists"));
        }

        if (cmd.getLineItems() == null || cmd.getLineItems().isEmpty()) {
            log.warn("CreateTicketCommand rejected — no line items orderId={}", cmd.getOrderId());
            return withFailure(new TicketCreationFailedReply("Order must have at least one item"));
        }

        if (cmd.getOrderTotal() == null || cmd.getOrderTotal().signum() <= 0) {
            log.warn("CreateTicketCommand rejected — invalid total orderId={}", cmd.getOrderId());
            return withFailure(new TicketCreationFailedReply("Order total must be greater than zero"));
        }

        Ticket ticket = new Ticket();
        ticket.setId(cmd.getTicketId());
        ticket.setOrderId(cmd.getOrderId());
        ticket.setConsumerId(cmd.getConsumerId());
        ticket.setOrderTotal(cmd.getOrderTotal());
        ticket.setStatus(TicketStatus.CREATE_PENDING);
        ticketRepository.save(ticket);

        log.info("Ticket {} created in CREATE_PENDING state", cmd.getTicketId());
        return withSuccess(new TicketCreatedReply("created"));
    }

    @Timed(value = "saga.command.approve_ticket", description = "Time taken to handle ApproveTicketCommand")
    @Transactional
    public Message approveTicket(CommandMessage<ApproveTicketCommand> cm) {
        ApproveTicketCommand cmd = cm.getCommand();
        log.info("Handling ApproveTicketCommand ticketId={}", cmd.getTicketId());

        Ticket ticket = ticketRepository.findById(cmd.getTicketId()).orElseThrow(
                () -> new IllegalStateException("Ticket not found: " + cmd.getTicketId()));

        if (ticket.getStatus() == TicketStatus.AWAITING_ACCEPTANCE
                || ticket.getStatus() == TicketStatus.APPROVED) {
            log.warn("[IDEMPOTENT] Ticket {} already approved/awaiting", cmd.getTicketId());
            return withSuccess(new TicketApprovedReply("already-approved"));
        }

        ticket.setStatus(TicketStatus.AWAITING_ACCEPTANCE);
        ticketRepository.save(ticket);
        log.info("Ticket {} moved to AWAITING_ACCEPTANCE", cmd.getTicketId());
        return withSuccess(new TicketApprovedReply("approved"));
    }

    @Timed(value = "saga.command.reject_ticket", description = "Time taken to handle RejectTicketCommand (compensation)")
    @Transactional
    public Message rejectTicket(CommandMessage<RejectTicketCommand> cm) {
        RejectTicketCommand cmd = cm.getCommand();
        log.info("Handling RejectTicketCommand ticketId={} (compensation)", cmd.getTicketId());

        Ticket ticket = ticketRepository.findById(cmd.getTicketId()).orElse(null);

        if (ticket == null) {
            // Ticket was never created (creation failed before save) — nothing to undo
            log.warn("Ticket {} not found during compensation — treating as already compensated", cmd.getTicketId());
            return withSuccess(new TicketRejectedReply("not-found-ok"));
        }

        if (ticket.getStatus() == TicketStatus.CREATE_REJECTED) {
            log.warn("[IDEMPOTENT] Ticket {} already rejected", cmd.getTicketId());
            return withSuccess(new TicketRejectedReply("already-rejected"));
        }

        ticket.setStatus(TicketStatus.CREATE_REJECTED);
        ticket.setRejectionReason("Saga compensation — credit card authorization failed");
        ticketRepository.save(ticket);
        log.info("Ticket {} moved to CREATE_REJECTED (compensation complete)", cmd.getTicketId());
        return withSuccess(new TicketRejectedReply("rejected"));
    }
}
