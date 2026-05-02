package com.saga.common.reply;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @AllArgsConstructor @NoArgsConstructor
public class CardAuthorizedReply {
    public static final String TYPE = "CardAuthorizedReply";
    private String message;
}
