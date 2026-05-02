package com.saga.accounting.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "accounts")
@Data
@NoArgsConstructor
public class Account {
    @Id private String consumerId;
    private String consumerName;
    private BigDecimal availableCredit;
    @Version private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @PrePersist  protected void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate   protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
