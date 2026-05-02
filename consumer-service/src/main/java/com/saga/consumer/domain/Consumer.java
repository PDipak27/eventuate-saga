package com.saga.consumer.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Entity
@Table(name = "consumers")
@Data
@NoArgsConstructor
public class Consumer {
    @Id private String id;
    private String name;
    private String email;
    private BigDecimal creditLimit;
    private boolean active;
}
