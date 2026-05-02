package com.saga.accounting.repository;

import com.saga.accounting.domain.AuthorizationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface AuthorizationRecordRepository extends JpaRepository<AuthorizationRecord, Long> {
    Optional<AuthorizationRecord> findByConsumerIdAndOrderId(String consumerId, String orderId);
}