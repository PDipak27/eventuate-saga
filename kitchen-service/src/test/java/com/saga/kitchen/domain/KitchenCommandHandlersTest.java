package com.saga.kitchen.domain;

import com.saga.common.command.ApproveTicketCommand;
import com.saga.common.command.CreateTicketCommand;
import com.saga.common.command.RejectTicketCommand;
import com.saga.common.dto.TicketLineItem;
import com.saga.kitchen.repository.TicketRepository;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("KitchenCommandHandlers — idempotency guards on all three commands")
class KitchenCommandHandlersTest {

    @Mock TicketRepository ticketRepository;
    @InjectMocks KitchenCommandHandlers handlers;

    private Ticket existingTicket;

    @BeforeEach
    void setUp() {
        existingTicket = new Ticket();
        existingTicket.setId("t-1");
        existingTicket.setOrderId("o-1");
        existingTicket.setConsumerId("c-1");
        existingTicket.setOrderTotal(new BigDecimal("29.99"));
        existingTicket.setStatus(TicketStatus.CREATE_PENDING);
    }

    // ── CreateTicket ───────────────────────────────────────────────────────────

    private CommandMessage<CreateTicketCommand> createCmd(String ticketId) {
        CreateTicketCommand cmd = new CreateTicketCommand();
        cmd.setTicketId(ticketId);
        cmd.setOrderId("o-1");
        cmd.setConsumerId("c-1");
        cmd.setOrderTotal(new BigDecimal("29.99"));
        cmd.setLineItems(List.of(new TicketLineItem("m1", "Pizza", 1, new BigDecimal("29.99"))));
        CommandMessage<CreateTicketCommand> cm = mock(CommandMessage.class);
        when(cm.getCommand()).thenReturn(cmd);
        return cm;
    }

    @Test
    @DisplayName("createTicket — happy path → ticket saved in CREATE_PENDING")
    void createTicket_happyPath() {
        when(ticketRepository.existsById("t-1")).thenReturn(false);

        handlers.createTicket(createCmd("t-1"));

        ArgumentCaptor<Ticket> captor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(TicketStatus.CREATE_PENDING);
    }

    @Test
    @DisplayName("createTicket — idempotency: ticket already exists → success reply, no second INSERT")
    void createTicket_alreadyExists_noDoubleInsert() {
        when(ticketRepository.existsById("t-1")).thenReturn(true);

        Message reply = handlers.createTicket(createCmd("t-1"));

        assertThat(reply).isNotNull();
        verify(ticketRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTicket — duplicate command twice: first inserts, second skips")
    void createTicket_duplicateCommand_firstInsertsSecondSkips() {
        when(ticketRepository.existsById("t-1"))
                .thenReturn(false)   // first call
                .thenReturn(true);   // second call (retry)

        handlers.createTicket(createCmd("t-1"));
        handlers.createTicket(createCmd("t-1"));

        verify(ticketRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("createTicket — no line items → failure reply, no save")
    void createTicket_noLineItems_failure() {
        CreateTicketCommand cmd = new CreateTicketCommand();
        cmd.setTicketId("t-2");
        cmd.setOrderId("o-2");
        cmd.setOrderTotal(new BigDecimal("29.99"));
        cmd.setLineItems(List.of());
        CommandMessage<CreateTicketCommand> cm = mock(CommandMessage.class);
        when(cm.getCommand()).thenReturn(cmd);
        when(ticketRepository.existsById("t-2")).thenReturn(false);

        handlers.createTicket(cm);

        verify(ticketRepository, never()).save(any());
    }

    // ── ApproveTicket ──────────────────────────────────────────────────────────

    private CommandMessage<ApproveTicketCommand> approveCmd(String ticketId) {
        ApproveTicketCommand cmd = new ApproveTicketCommand();
        cmd.setTicketId(ticketId);
        CommandMessage<ApproveTicketCommand> cm = mock(CommandMessage.class);
        when(cm.getCommand()).thenReturn(cmd);
        return cm;
    }

    @Test
    @DisplayName("approveTicket — happy path → status set to AWAITING_ACCEPTANCE")
    void approveTicket_happyPath() {
        existingTicket.setStatus(TicketStatus.CREATE_PENDING);
        when(ticketRepository.findById("t-1")).thenReturn(Optional.of(existingTicket));

        handlers.approveTicket(approveCmd("t-1"));

        assertThat(existingTicket.getStatus()).isEqualTo(TicketStatus.AWAITING_ACCEPTANCE);
        verify(ticketRepository).save(existingTicket);
    }

    @Test
    @DisplayName("approveTicket — idempotency: already AWAITING_ACCEPTANCE → no second save")
    void approveTicket_alreadyAwaiting_noDoubleUpdate() {
        existingTicket.setStatus(TicketStatus.AWAITING_ACCEPTANCE);
        when(ticketRepository.findById("t-1")).thenReturn(Optional.of(existingTicket));

        handlers.approveTicket(approveCmd("t-1"));

        verify(ticketRepository, never()).save(any());
    }

    @Test
    @DisplayName("approveTicket — idempotency: already APPROVED → no second save")
    void approveTicket_alreadyApproved_noDoubleUpdate() {
        existingTicket.setStatus(TicketStatus.APPROVED);
        when(ticketRepository.findById("t-1")).thenReturn(Optional.of(existingTicket));

        handlers.approveTicket(approveCmd("t-1"));

        verify(ticketRepository, never()).save(any());
    }

    // ── RejectTicket ───────────────────────────────────────────────────────────

    private CommandMessage<RejectTicketCommand> rejectCmd(String ticketId) {
        RejectTicketCommand cmd = new RejectTicketCommand();
        cmd.setTicketId(ticketId);
        CommandMessage<RejectTicketCommand> cm = mock(CommandMessage.class);
        when(cm.getCommand()).thenReturn(cmd);
        return cm;
    }

    @Test
    @DisplayName("rejectTicket — happy path → status set to CREATE_REJECTED")
    void rejectTicket_happyPath() {
        existingTicket.setStatus(TicketStatus.CREATE_PENDING);
        when(ticketRepository.findById("t-1")).thenReturn(Optional.of(existingTicket));

        handlers.rejectTicket(rejectCmd("t-1"));

        assertThat(existingTicket.getStatus()).isEqualTo(TicketStatus.CREATE_REJECTED);
        verify(ticketRepository).save(existingTicket);
    }

    @Test
    @DisplayName("rejectTicket — idempotency: already CREATE_REJECTED → no second save")
    void rejectTicket_alreadyRejected_noDoubleUpdate() {
        existingTicket.setStatus(TicketStatus.CREATE_REJECTED);
        when(ticketRepository.findById("t-1")).thenReturn(Optional.of(existingTicket));

        handlers.rejectTicket(rejectCmd("t-1"));

        verify(ticketRepository, never()).save(any());
    }

    @Test
    @DisplayName("rejectTicket — compensation on non-existent ticket → success reply (safe no-op)")
    void rejectTicket_ticketNotFound_safeNoOp() {
        when(ticketRepository.findById("t-999")).thenReturn(Optional.empty());

        Message reply = handlers.rejectTicket(rejectCmd("t-999"));

        assertThat(reply).isNotNull();
        verify(ticketRepository, never()).save(any());
    }
}