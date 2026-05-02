package com.saga.order.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.saga.order.saga.OrderCommandHandlers;

import io.eventuate.tram.commands.consumer.CommandDispatcher;
import io.eventuate.tram.commands.consumer.CommandDispatcherFactory;
@Configuration
public class OrderServiceConfig {

    

    /**
     * CommandDispatcher receives ApproveOrderCommand and RejectOrderCommand
     * from the "orderService" RabbitMQ channel and dispatches to handlers.
     */
    @Bean
    public CommandDispatcher orderCommandDispatcher(
            OrderCommandHandlers handlers,
            CommandDispatcherFactory factory) {
        return factory.make("orderServiceCommandDispatcher", handlers.commandHandlers());
    }
}
