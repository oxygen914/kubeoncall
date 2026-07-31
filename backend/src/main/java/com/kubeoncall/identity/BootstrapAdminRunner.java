package com.kubeoncall.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;

/**
 * One-time initial administrator bootstrap. Activated only when
 * {@code kubeoncall.auth.bootstrap-admin-enabled=true} and a username/password are provided. After a
 * successful create the runner logs a prominent warning to remove the bootstrap environment
 * variables, since keeping them around would let anyone who can set env recreate an admin. Failures
 * (unavailable, missing credentials, duplicate) are logged and never crash startup.
 */
@Component
@Order(20)
public class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private final KubeOnCallProperties properties;
    private final IdentityAdminService adminService;

    public BootstrapAdminRunner(KubeOnCallProperties properties, IdentityAdminService adminService) {
        this.properties = properties;
        this.adminService = adminService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getAuth().isBootstrapAdminEnabled()) {
            return;
        }
        String username = properties.getAuth().getBootstrapAdminUsername();
        String password = properties.getAuth().getBootstrapAdminPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.warn("Bootstrap admin enabled but username or password not configured; skipping");
            return;
        }
        if (!adminService.isAvailable()) {
            log.warn("Bootstrap admin enabled but MySQL identity repository is unavailable; skipping");
            return;
        }
        IdentityAdminService.CreateUserResult result =
                adminService.createAdmin(username, username, password.toCharArray());
        switch (result.status()) {
            case CREATED ->
                log.warn(
                        "Bootstrap admin '{}' created. REMOVE KUBEONCALL_BOOTSTRAP_ADMIN_* environment variables now to prevent re-creation.",
                        username);
            case ALREADY_EXISTS -> log.info("Bootstrap admin '{}' already exists; no action", username);
            case UNAVAILABLE -> log.warn("Bootstrap admin unavailable; skipping");
        }
    }
}
