package com.saga.common.reply;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @AllArgsConstructor @NoArgsConstructor
public class ConsumerVerificationFailedReply {
    public static final String TYPE = "ConsumerVerificationFailedReply";
    private String message;
}
