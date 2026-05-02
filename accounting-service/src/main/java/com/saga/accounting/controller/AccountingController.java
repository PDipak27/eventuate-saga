package com.saga.accounting.controller;

import com.saga.accounting.domain.Account;
import com.saga.accounting.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class AccountingController {

    private final AccountRepository accountRepository;

    @PostMapping
    public ResponseEntity<Map<String, String>> createAccount(@RequestBody CreateAccountRequest req) {
        if (req.consumerId() == null || req.consumerId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "consumerId is required"));
        }

        if (accountRepository.existsById(req.consumerId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("consumerId", req.consumerId(), "status", "already_exists"));
        }

        Account account = new Account();
        account.setConsumerId(req.consumerId());
        account.setConsumerName(req.consumerName());
        account.setAvailableCredit(req.availableCredit());
        accountRepository.save(account);

        return ResponseEntity.ok(Map.of("consumerId", req.consumerId(), "status", "created"));
    }

    @GetMapping("/{consumerId}")
    public ResponseEntity<Account> getAccount(@PathVariable String consumerId) {
        return accountRepository.findById(consumerId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    public record CreateAccountRequest(
            String consumerId, String consumerName, BigDecimal availableCredit) {}
}
