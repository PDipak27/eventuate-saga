package com.saga.accounting.config;

import com.saga.accounting.domain.AccountingCommandHandlers;
import io.eventuate.tram.commands.consumer.CommandDispatcher;
import io.eventuate.tram.commands.consumer.CommandDispatcherFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AccountingServiceConfig {

    @Bean
    public CommandDispatcher accountingCommandDispatcher(
            AccountingCommandHandlers handlers,
            CommandDispatcherFactory factory) {
        return factory.make("accountingServiceCommandDispatcher", handlers.commandHandlers());
    }
}
