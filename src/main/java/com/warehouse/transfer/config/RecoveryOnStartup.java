package com.warehouse.transfer.config;

import com.warehouse.transfer.service.RecoveryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class RecoveryOnStartup implements ApplicationRunner {

    private final RecoveryService recoveryService;
    private final boolean enabled;

    public RecoveryOnStartup(
            RecoveryService recoveryService,
            @Value("${app.recovery.enabled:true}") boolean enabled
    ) {
        this.recoveryService = recoveryService;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        recoveryService.resumeInterrupted();
    }
}
