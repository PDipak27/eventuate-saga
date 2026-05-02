package com.saga.common.reply;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @AllArgsConstructor @NoArgsConstructor
public class CardAuthorizationFailedReply {
    public static final String TYPE = "CardAuthorizationFailedReply";
    private String message;
}
