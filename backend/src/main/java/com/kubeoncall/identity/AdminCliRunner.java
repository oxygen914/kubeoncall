package com.kubeoncall.identity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Interactive {@code admin create} CLI. Invoked via
 * {@code ./mvnw spring-boot:run -Dspring-boot.run.arguments="admin create --username admin"}.
 *
 * <p>Reads the password from {@code --password} if supplied, otherwise from stdin (so it never lands
 * in shell history). Exits the process after running so the full web server does not start for a
 * one-shot admin creation. The username must not already exist; re-running with the same username is
 * a clean no-op exit code 0.
 */
@Component
@Order(10)
public class AdminCliRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminCliRunner.class);

    private final IdentityAdminService adminService;

    public AdminCliRunner(IdentityAdminService adminService) {
        this.adminService = adminService;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> nonOption = args.getNonOptionArgs();
        if (nonOption.isEmpty()
                || !"admin".equals(nonOption.get(0))
                || nonOption.size() < 2
                || !"create".equals(nonOption.get(1))) {
            return;
        }
        String username = option(args, "username");
        String password = option(args, "password");
        String displayName = option(args, "display-name");
        if (username == null || username.isBlank()) {
            System.err.println("Usage: admin create --username <name> [--password <pw>] [--display-name <name>]");
            System.exit(2);
            return;
        }
        if (!adminService.isAvailable()) {
            System.err.println(
                    "MySQL identity repository is not available. Set KUBEONCALL_MYSQL_ENABLED=true and configure the datasource.");
            System.exit(1);
            return;
        }
        if (password == null || password.isBlank()) {
            password = readPasswordFromStdin();
            if (password == null) {
                System.err.println("No password provided");
                System.exit(2);
                return;
            }
        }
        IdentityAdminService.CreateUserResult result = adminService.createAdmin(
                username, displayName == null ? username : displayName, password.toCharArray());
        switch (result.status()) {
            case CREATED -> {
                System.out.println("Created admin user '" + username + "' (id=" + result.publicId() + ")");
                System.exit(0);
            }
            case ALREADY_EXISTS -> {
                System.out.println("Admin user '" + username + "' already exists");
                System.exit(0);
            }
            case UNAVAILABLE -> {
                System.err.println("Identity repository unavailable");
                System.exit(1);
            }
        }
    }

    private static String option(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String readPasswordFromStdin() {
        try {
            System.err.print("Enter password (>= 12 chars): ");
            System.err.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            return line == null ? null : line;
        } catch (IOException ex) {
            log.warn("Failed to read password from stdin: {}", ex.getMessage());
            return null;
        }
    }
}
