package com.saga.kitchen.controller;

import com.saga.kitchen.domain.Ticket;
import com.saga.kitchen.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/tickets")
@RequiredArgsConstructor
public class KitchenController {

    private final TicketRepository ticketRepository;

    @GetMapping("/{ticketId}")
    public ResponseEntity<Ticket> getTicket(@PathVariable String ticketId) {
        return ticketRepository.findById(ticketId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<Ticket> getByOrderId(@PathVariable String orderId) {
        return ticketRepository.findByOrderId(orderId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<Ticket>> getAll() {
        return ResponseEntity.ok(ticketRepository.findAll());
    }
}
