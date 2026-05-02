package com.saga.common.command;

import io.eventuate.tram.commands.common.Command;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @AllArgsConstructor @NoArgsConstructor
public class ApproveOrderCommand implements Command {
    public static final String CHANNEL = "orderService";
     private String orderId;}
