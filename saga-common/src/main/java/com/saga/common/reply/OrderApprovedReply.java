package com.saga.common.reply;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @AllArgsConstructor @NoArgsConstructor
public class OrderApprovedReply {
    public static final String TYPE = "OrderApprovedReply";
    private String message;
}
