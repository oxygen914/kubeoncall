package com.kubeoncall.common.concurrent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class LeaseHeartbeatTest {

    @Test
    void shouldInvalidateLeaseWhenRenewalFails() throws Exception {
        CountDownLatch renewed = new CountDownLatch(1);

        try (LeaseHeartbeat heartbeat = LeaseHeartbeat.start(
                Duration.ofMillis(300),
                () -> {
                    renewed.countDown();
                    return false;
                },
                "lease-heartbeat-test")) {
            assertTrue(renewed.await(2, TimeUnit.SECONDS));
            assertFalse(heartbeat.isValid());
        }
    }
}
