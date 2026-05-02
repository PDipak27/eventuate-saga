package com.saga.consumer.controller;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.saga.consumer.domain.Consumer;
import com.saga.consumer.repository.ConsumerRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/consumers")
@RequiredArgsConstructor
@Slf4j
public class ConsumerController {

    private final ConsumerRepository consumerRepository;

    @PostMapping
    public ResponseEntity<Map<String, String>> createConsumer(@RequestBody CreateConsumerRequest req) {
        String id = req.consumerId() != null ? req.consumerId() : UUID.randomUUID().toString();

        if (consumerRepository.existsById(id)) {
        	log.warn("[ConsumerController} Consumer already exists. skipping. consumerId={}",
        			req.consumerId());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("consumerId", id, "status", "already_exists"));
        }

        Consumer c = new Consumer();
        c.setId(id);
        c.setName(req.name());
        c.setEmail(req.email());
        c.setCreditLimit(req.creditLimit());
        c.setActive(true);
        consumerRepository.save(c);

        return ResponseEntity.ok(Map.of("consumerId", id, "status", "created"));
    }

    @GetMapping("/{consumerId}")
    public ResponseEntity<Consumer> getConsumer(@PathVariable String consumerId) {
        return consumerRepository.findById(consumerId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    public record CreateConsumerRequest(
            String consumerId, String name, String email, BigDecimal creditLimit) {}
}
