package com.kubeoncall.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kubeoncall.notification.application.NotificationDeliveryRequest;
import com.kubeoncall.notification.application.NotificationDeliveryResult;
import com.kubeoncall.notification.delivery.NotificationDeliveryRecord;
import com.kubeoncall.notification.delivery.NotificationDeliveryRepository;
import com.kubeoncall.notification.delivery.NotificationDeliveryStatus;
import com.kubeoncall.notification.domain.NotificationCapability;
import com.kubeoncall.notification.domain.NotificationDestination;
import com.kubeoncall.notification.domain.NotificationMessage;
import com.kubeoncall.notification.domain.NotificationPriority;

/** Verifies the V19 ledger and state transitions against real MySQL 8. */
@Testcontainers
class NotificationDeliveryMySqlIT {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("kubeoncall")
            .withUsername("kubeoncall")
            .withPassword("test-password")
            .withReuse(false);

    private static NotificationDeliveryRepository repository;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void setUp() {
        String jdbcUrl = MYSQL.getJdbcUrl() + "?allowPublicKeyRetrieval=true&useSSL=false";
        DataSource dataSource = DataSourceBuilder.create()
                .url(jdbcUrl)
                .username(MYSQL.getUsername())
                .password(MYSQL.getPassword())
                .build();
        org.flywaydb.core.Flyway.configure()
                .dataSource(jdbcUrl, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new NotificationDeliveryRepository(jdbcTemplate, objectMapper);
    }

    @Test
    void createsOnceTransitionsAndRequeuesTerminalFailure() {
        Instant now = Instant.parse("2026-07-30T01:00:00Z");
        NotificationMessage message = message("event-mysql-1");
        NotificationDestination destination = destination();

        var created = repository.createIfAbsent(
                "ndlv_mysql_1", "delivery-key-mysql-1", message, destination, "request-1", now);
        var duplicate = repository.createIfAbsent(
                "ndlv_mysql_1", "delivery-key-mysql-1", message, destination, "request-1", now);

        assertThat(created.created()).isTrue();
        assertThat(duplicate.created()).isFalse();
        assertThat(repository.list(1, 20).total()).isEqualTo(1);

        repository.markProcessing("ndlv_mysql_1", 1, now.plusSeconds(1));
        NotificationDeliveryResult failure = NotificationDeliveryResult.failed(
                new NotificationDeliveryRequest("ndlv_mysql_1", message, destination),
                "feishu",
                "FEISHU_TARGET_NOT_CONFIGURED",
                "target is missing",
                false);
        repository.markFailed("ndlv_mysql_1", 1, failure, NotificationDeliveryStatus.FAILED, now.plusSeconds(2));

        NotificationDeliveryRecord failed = repository.findByPublicId("ndlv_mysql_1");
        assertThat(failed.status()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(failed.lastErrorCode()).isEqualTo("FEISHU_TARGET_NOT_CONFIGURED");

        assertThat(repository.requeue("ndlv_mysql_1", NotificationDeliveryStatus.FAILED, now.plusSeconds(3)))
                .isTrue();
        NotificationDeliveryRecord replayed = repository.findByPublicId("ndlv_mysql_1");
        assertThat(replayed.status()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(replayed.attempt()).isZero();
        assertThat(replayed.replayCount()).isEqualTo(1);

        jdbcTemplate.update("""
                INSERT INTO koc_outbox_event
                  (event_id, aggregate_type, aggregate_public_id, event_type, payload_json, status)
                VALUES (?, 'NOTIFICATION_DELIVERY', ?, 'notification.delivery.requested', '{}', 'DEAD_LETTER')
                """, "oevt_notification_mysql_1", "ndlv_mysql_1");
        assertThat(repository.reconcileDeadLetters(now.plusSeconds(4))).isEqualTo(1);
        assertThat(repository.findByPublicId("ndlv_mysql_1").status())
                .isEqualTo(NotificationDeliveryStatus.DEAD_LETTER);
    }

    private static NotificationMessage message(String eventId) {
        return new NotificationMessage(
                eventId,
                "alarm.firing.initial",
                "oncall",
                NotificationPriority.HIGH,
                "NodeDown",
                "node is unavailable",
                Map.of("resource", "node-a"),
                List.of(),
                Instant.parse("2026-07-30T01:00:00Z"));
    }

    private static NotificationDestination destination() {
        return new NotificationDestination(
                "feishu-primary", "feishu", "infra", Set.of(NotificationCapability.GROUP_WEBHOOK), Map.of());
    }
}
