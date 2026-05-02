package com.saga.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TicketLineItem {
    private String menuItemId;
    private String name;
    private int quantity;
    private BigDecimal price;
}
