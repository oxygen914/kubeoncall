package com.kubeoncall.audit.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class OutboxRepositoryTest {

    @Test
    void computesCappedExponentialBackoffFromClaimedAttempt() {
        Duration initial = Duration.ofSeconds(2);
        Duration maximum = Duration.ofSeconds(10);

        assertEquals(Duration.ofSeconds(2), OutboxRepository.exponentialBackoff(initial, maximum, 1));
        assertEquals(Duration.ofSeconds(4), OutboxRepository.exponentialBackoff(initial, maximum, 2));
        assertEquals(Duration.ofSeconds(8), OutboxRepository.exponentialBackoff(initial, maximum, 3));
        assertEquals(Duration.ofSeconds(10), OutboxRepository.exponentialBackoff(initial, maximum, 4));
        assertEquals(Duration.ofSeconds(10), OutboxRepository.exponentialBackoff(initial, maximum, 20));
    }
}
