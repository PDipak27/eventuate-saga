package com.saga.kitchen.config;

import com.saga.kitchen.domain.KitchenCommandHandlers;
import io.eventuate.tram.commands.consumer.CommandDispatcher;
import io.eventuate.tram.commands.consumer.CommandDispatcherFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KitchenServiceConfig {

    @Bean
    public CommandDispatcher kitchenCommandDispatcher(
            KitchenCommandHandlers handlers,
            CommandDispatcherFactory factory) {
        return factory.make("kitchenServiceCommandDispatcher", handlers.commandHandlers());
    }
}
