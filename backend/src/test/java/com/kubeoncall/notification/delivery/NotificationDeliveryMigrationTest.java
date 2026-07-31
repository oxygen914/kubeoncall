package com.kubeoncall.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class NotificationDeliveryMigrationTest {

    @Test
    void migrationDefinesPerDestinationIdempotencyAndOperationalIndexes() throws IOException {
        String sql;
        try (InputStream input = getClass().getResourceAsStream("/db/migration/V19__notification_delivery.sql")) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("CREATE TABLE koc_notification_delivery")
                .contains("UNIQUE KEY uk_notification_delivery_key")
                .contains("provider_key VARCHAR(64)")
                .contains("destination_id VARCHAR(128)")
                .contains("external_message_id VARCHAR(255)")
                .contains("attempt INT UNSIGNED")
                .contains("idx_notification_delivery_status")
                .contains("chk_notification_delivery_operation")
                .contains("chk_notification_delivery_status")
                .contains("'DEAD_LETTER'");
    }
}
