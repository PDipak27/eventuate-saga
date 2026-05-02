package com.saga.common.reply;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @AllArgsConstructor @NoArgsConstructor
public class OrderRejectedReply {
    public static final String TYPE = "OrderRejectedReply";
    private String message;
}
