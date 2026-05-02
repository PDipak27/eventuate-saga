package com.saga.common.command;

import io.eventuate.tram.commands.common.Command;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data @AllArgsConstructor @NoArgsConstructor
public class VerifyConsumerCommand implements Command {
    public static final String CHANNEL = "consumerService";
    private String consumerId;
    private String orderId;
    private BigDecimal orderTotal;
}
