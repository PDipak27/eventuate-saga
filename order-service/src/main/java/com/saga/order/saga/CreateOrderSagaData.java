package com.saga.order.saga;

import com.saga.common.dto.TicketLineItem;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateOrderSagaData {
    private String orderId;
    private String consumerId;
    private String ticketId;
    private BigDecimal orderTotal;
    private List<TicketLineItem> lineItems;
    private String rejectionReason;
}
