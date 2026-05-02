package com.saga.consumer.config;

import com.saga.consumer.domain.ConsumerCommandHandlers;
import io.eventuate.tram.commands.consumer.CommandDispatcher;
import io.eventuate.tram.commands.consumer.CommandDispatcherFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ConsumerServiceConfig {

    @Bean
    public CommandDispatcher consumerCommandDispatcher(
            ConsumerCommandHandlers handlers,
            CommandDispatcherFactory factory) {
        return factory.make("consumerServiceCommandDispatcher", handlers.commandHandlers());
    }
}
