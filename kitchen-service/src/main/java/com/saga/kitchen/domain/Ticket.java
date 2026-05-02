package com.saga.kitchen.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "tickets")
@Data
@NoArgsConstructor
public class Ticket {
    @Id private String id;
    private String orderId;
    private String consumerId;
    @Enumerated(EnumType.STRING) private TicketStatus status;
    private BigDecimal orderTotal;
    private String rejectionReason;
    @Version private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @PrePersist  protected void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate   protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
