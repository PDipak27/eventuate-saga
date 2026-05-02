package com.saga.order.saga;

import com.saga.common.command.ApproveOrderCommand;
import com.saga.common.command.RejectOrderCommand;
import com.saga.order.domain.Order;
import com.saga.order.domain.OrderStatus;
import com.saga.order.repository.OrderRepository;
import io.eventuate.tram.commands.consumer.CommandMessage;
import io.eventuate.tram.messaging.common.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderCommandHandlers — idempotency on retriable steps")
class OrderCommandHandlersTest {

    @Mock OrderRepository orderRepository;
    @InjectMocks OrderCommandHandlers handlers;

    private Order pendingOrder;

    @BeforeEach
    void setUp() {
        pendingOrder = new Order();
        pendingOrder.setId("o-1");
        pendingOrder.setStatus(OrderStatus.APPROVAL_PENDING);
    }

    // ── ApproveOrder ───────────────────────────────────────────────────────────

    private CommandMessage<ApproveOrderCommand> approveCmd(String orderId) {
        ApproveOrderCommand cmd = new ApproveOrderCommand();
        cmd.setOrderId(orderId);
        CommandMessage<ApproveOrderCommand> cm = mock(CommandMessage.class);
        when(cm.getCommand()).thenReturn(cmd);
        return cm;
    }

    @Test
    @DisplayName("approve — happy path → order status set to APPROVED")
    void approve_happyPath() {
        when(orderRepository.findById("o-1")).thenReturn(Optional.of(pendingOrder));

        handlers.approve(approveCmd("o-1"));

        assertThat(pendingOrder.getStatus()).isEqualTo(OrderStatus.APPROVED);
        verify(orderRepository).save(pendingOrder);
    }

    @Test
    @DisplayName("approve — idempotency: already APPROVED → success reply, no second save")
    void approve_alreadyApproved_noDoubleUpdate() {
        pendingOrder.setStatus(OrderStatus.APPROVED);
        when(orderRepository.findById("o-1")).thenReturn(Optional.of(pendingOrder));

        Message reply = handlers.approve(approveCmd("o-1"));

        assertThat(reply).isNotNull();
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("approve — duplicate command twice: first saves, second skips")
    void approve_duplicateCommand_firstSavesSecondSkips() {
        Order firstState = new Order();
        firstState.setId("o-1");
        firstState.setStatus(OrderStatus.APPROVAL_PENDING);

        Order secondState = new Order();
        secondState.setId("o-1");
        secondState.setStatus(OrderStatus.APPROVED);

        when(orderRepository.findById("o-1"))
                .thenReturn(Optional.of(firstState))
                .thenReturn(Optional.of(secondState));

        handlers.approve(approveCmd("o-1"));
        handlers.approve(approveCmd("o-1"));

        verify(orderRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("approve — order not found → exception (not a valid saga state)")
    void approve_orderNotFound_throws() {
        when(orderRepository.findById("o-999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handlers.approve(approveCmd("o-999")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("o-999");
    }

    // ── RejectOrder ────────────────────────────────────────────────────────────

    private CommandMessage<RejectOrderCommand> rejectCmd(String orderId, String reason) {
        RejectOrderCommand cmd = new RejectOrderCommand();
        cmd.setOrderId(orderId);
        cmd.setReason(reason);
        CommandMessage<RejectOrderCommand> cm = mock(CommandMessage.class);
        when(cm.getCommand()).thenReturn(cmd);
        return cm;
    }

    @Test
    @DisplayName("reject — happy path → order status set to REJECTED with reason")
    void reject_happyPath() {
        when(orderRepository.findById("o-1")).thenReturn(Optional.of(pendingOrder));

        handlers.reject(rejectCmd("o-1", "Insufficient credit"));

        assertThat(pendingOrder.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(pendingOrder.getRejectionReason()).isEqualTo("Insufficient credit");
        verify(orderRepository).save(pendingOrder);
    }

    @Test
    @DisplayName("reject — idempotency: already REJECTED → success reply, no second save")
    void reject_alreadyRejected_noDoubleUpdate() {
        pendingOrder.setStatus(OrderStatus.REJECTED);
        pendingOrder.setRejectionReason("Insufficient credit");
        when(orderRepository.findById("o-1")).thenReturn(Optional.of(pendingOrder));

        Message reply = handlers.reject(rejectCmd("o-1", "Insufficient credit"));

        assertThat(reply).isNotNull();
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("reject — duplicate command twice: first saves, second skips")
    void reject_duplicateCommand_firstSavesSecondSkips() {
        Order firstState = new Order();
        firstState.setId("o-1");
        firstState.setStatus(OrderStatus.APPROVAL_PENDING);

        Order secondState = new Order();
        secondState.setId("o-1");
        secondState.setStatus(OrderStatus.REJECTED);

        when(orderRepository.findById("o-1"))
                .thenReturn(Optional.of(firstState))
                .thenReturn(Optional.of(secondState));

        handlers.reject(rejectCmd("o-1", "reason"));
        handlers.reject(rejectCmd("o-1", "reason"));

        verify(orderRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("reject — order not found → exception (not a valid saga state)")
    void reject_orderNotFound_throws() {
        when(orderRepository.findById("o-999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handlers.reject(rejectCmd("o-999", "reason")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("o-999");
    }
}