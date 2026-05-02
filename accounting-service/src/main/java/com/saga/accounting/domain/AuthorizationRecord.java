package com.saga.accounting.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Idempotency record for card authorization.
 * One row per (consumerId, orderId) — prevents double-authorization on retry.
 * Written in the same @Transactional as the reply message insert.
 */
@Entity
@Table(name = "authorization_records",
       uniqueConstraints = @UniqueConstraint(columnNames = {"consumer_id", "order_id"}))
@Data
@NoArgsConstructor
public class AuthorizationRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consumer_id", nullable = false)
    private String consumerId;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private boolean authorized;

    private String failureReason;

    private LocalDateTime createdAt;

    @PrePersist protected void onCreate() { createdAt = LocalDateTime.now(); }
}