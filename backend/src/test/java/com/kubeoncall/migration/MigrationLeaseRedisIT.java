package com.kubeoncall.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Real Redis proof that a stale migration runner cannot delete a newer runner's lease. */
class MigrationLeaseRedisIT {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void staleHolderCannotReleaseLeaseReacquiredByAnotherRunner() {
        String key = "kubeoncall:test:migration-lease:" + UUID.randomUUID();
        try {
            redis.opsForValue().set(key, "new-holder");

            assertThat(MigrationLease.releaseIfOwned(redis, key, "old-holder")).isFalse();
            assertThat(redis.opsForValue().get(key)).isEqualTo("new-holder");
        } finally {
            redis.delete(key);
        }
    }

    @Test
    void holderCanReleaseItsOwnLease() {
        String key = "kubeoncall:test:migration-lease:" + UUID.randomUUID();
        try {
            redis.opsForValue().set(key, "holder");

            assertThat(MigrationLease.releaseIfOwned(redis, key, "holder")).isTrue();
            assertThat(redis.hasKey(key)).isFalse();
        } finally {
            redis.delete(key);
        }
    }

    @Test
    void holderCanRenewButStaleHolderCannotExtendTheNewLease() {
        String key = "kubeoncall:test:migration-lease:" + UUID.randomUUID();
        try {
            redis.opsForValue().set(key, "holder");
            assertThat(MigrationLease.renewIfOwned(redis, key, "holder")).isTrue();
            assertThat(redis.getExpire(key)).isPositive();

            redis.opsForValue().set(key, "new-holder");
            assertThat(MigrationLease.renewIfOwned(redis, key, "holder")).isFalse();
        } finally {
            redis.delete(key);
        }
    }
}
