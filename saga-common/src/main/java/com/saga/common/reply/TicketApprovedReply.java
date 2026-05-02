package com.saga.common.reply;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @AllArgsConstructor @NoArgsConstructor
public class TicketApprovedReply {
    public static final String TYPE = "TicketApprovedReply";
    private String message;
}
