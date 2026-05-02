package com.saga.common.reply;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @AllArgsConstructor @NoArgsConstructor
public class TicketRejectedReply {
    public static final String TYPE = "TicketRejectedReply";
    private String message;
}
