package com.kubeoncall.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Terminates the process after Spring Boot has applied Flyway migrations for a dedicated migration
 * Job. The Job uses a migration-only database account; regular application Pods set Flyway disabled
 * and therefore never need DDL privileges.
 */
@Component
@ConditionalOnProperty(name = "kubeoncall.migration-only", havingValue = "true")
public class MigrationOnlyExitRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationOnlyExitRunner.class);

    private final ConfigurableApplicationContext applicationContext;

    public MigrationOnlyExitRunner(ConfigurableApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Flyway migration completed; closing migration-only process");
        applicationContext.close();
    }
}
