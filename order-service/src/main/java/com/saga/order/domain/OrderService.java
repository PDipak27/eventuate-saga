package com.saga.order.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.saga.common.dto.TicketLineItem;
import com.saga.order.repository.OrderRepository;
import com.saga.order.saga.CreateOrderSaga;
import com.saga.order.saga.CreateOrderSagaData;

import io.eventuate.tram.sagas.orchestration.Saga;
import io.eventuate.tram.sagas.orchestration.SagaInstanceFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * OrderService creates the Order JPA record and the saga instance in a single
 * local transaction. Eventuate writes the first command to the outbox table in
 * the same transaction — guaranteed atomic via the transactional outbox pattern.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final SagaInstanceFactory sagaInstanceFactory;
    private final CreateOrderSaga createOrderSaga;

    @Transactional
    public Order createOrder(String consumerId, List<TicketLineItem> lineItems, BigDecimal orderTotal) {
        String orderId = UUID.randomUUID().toString();
        log.info("Creating order orderId={} consumerId={}", orderId, consumerId);

        Order order = new Order();
        order.setId(orderId);
        order.setConsumerId(consumerId);
        order.setStatus(OrderStatus.APPROVAL_PENDING);
        order.setOrderTotal(orderTotal);

        List<OrderLineItem> items = lineItems.stream().map(item -> {
            OrderLineItem li = new OrderLineItem();
            li.setOrder(order);
            li.setMenuItemId(item.getMenuItemId());
            li.setName(item.getName());
            li.setQuantity(item.getQuantity());
            li.setPrice(item.getPrice());
            return li;
        }).collect(Collectors.toList());
        order.setLineItems(items);

        // Save order — status APPROVAL_PENDING (semantic lock)
        orderRepository.save(order);

        // Start saga — writes saga_instance row AND first command to outbox,
        // all in the same @Transactional scope as the order INSERT above.
        CreateOrderSagaData sagaData = new CreateOrderSagaData(
                orderId, consumerId, null, orderTotal, lineItems, null);
        sagaInstanceFactory.create((Saga<CreateOrderSagaData>) createOrderSaga, sagaData);

        return order;
    }
}
