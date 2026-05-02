package com.saga.common.command;

import com.saga.common.dto.TicketLineItem;
import io.eventuate.tram.commands.common.Command;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.util.List;

@Data @AllArgsConstructor @NoArgsConstructor
public class CreateTicketCommand implements Command {
    public static final String CHANNEL = "kitchenService";
    private String ticketId;
    private String orderId;
    private String consumerId;
    private List<TicketLineItem> lineItems;
    private BigDecimal orderTotal;
}
