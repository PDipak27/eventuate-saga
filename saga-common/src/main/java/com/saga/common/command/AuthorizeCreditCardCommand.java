package com.saga.common.command;

import io.eventuate.tram.commands.common.Command;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data @AllArgsConstructor @NoArgsConstructor
public class AuthorizeCreditCardCommand implements Command {
    public static final String CHANNEL = "accountingService";
    private String consumerId;
    private String orderId;
    private BigDecimal orderTotal;
}
