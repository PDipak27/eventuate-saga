package com.saga.order.controller;

import com.saga.common.dto.TicketLineItem;
import com.saga.order.domain.Order;
import com.saga.order.domain.OrderService;
import com.saga.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;
    private final OrderRepository orderRepository;

    @PostMapping
    public ResponseEntity<Map<String, String>> createOrder(@RequestBody CreateOrderRequest req) {
        Order order = orderService.createOrder(req.consumerId(), req.lineItems(), req.orderTotal());
        return ResponseEntity.accepted().body(Map.of(
                "orderId", order.getId(),
                "status", order.getStatus().name()
        ));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<Order> getOrder(@PathVariable String orderId) {
        return orderRepository.findById(orderId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/consumer/{consumerId}")
    public ResponseEntity<List<Order>> getOrdersByConsumer(@PathVariable String consumerId) {
        return ResponseEntity.ok(orderRepository.findByConsumerIdOrderByCreatedAtDesc(consumerId));
    }

    public record CreateOrderRequest(
            String consumerId,
            List<TicketLineItem> lineItems,
            BigDecimal orderTotal) {}
}
