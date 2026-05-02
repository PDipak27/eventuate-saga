package com.saga.common.reply;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @AllArgsConstructor @NoArgsConstructor
public class TicketCreationFailedReply {
    public static final String TYPE = "TicketCreationFailedReply";
    private String message;
}
