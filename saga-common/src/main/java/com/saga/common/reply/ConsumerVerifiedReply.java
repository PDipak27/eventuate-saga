package com.saga.common.reply;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @AllArgsConstructor @NoArgsConstructor
public class ConsumerVerifiedReply {
    public static final String TYPE = "ConsumerVerifiedReply";
    private String message;
}
