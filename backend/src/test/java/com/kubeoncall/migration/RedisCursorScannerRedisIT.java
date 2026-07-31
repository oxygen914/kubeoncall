package com.kubeoncall.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Verifies that the persisted cursor passed to the next SCAN command reaches the remaining keys. */
class RedisCursorScannerRedisIT {

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
    void continuesFromTheReturnedCursorUntilTheWholeKeyspaceSliceIsComplete() {
        String prefix = "kubeoncall:test:cursor-scan:" + UUID.randomUUID() + ":";
        Set<String> expected = new LinkedHashSet<>();
        for (int index = 0; index < 64; index++) {
            String key = prefix + index;
            expected.add(key);
            redis.opsForValue().set(key, "value");
        }

        try {
            Set<String> observed = new LinkedHashSet<>();
            String cursor = "0";
            int pages = 0;
            do {
                RedisCursorScanner.Page page = RedisCursorScanner.scan(redis, cursor, prefix + "*", 3);
                observed.addAll(page.keys());
                cursor = page.nextCursor();
                pages++;
            } while (!"0".equals(cursor) && pages < 200);

            assertThat(pages).isGreaterThan(1);
            assertThat(cursor).isEqualTo("0");
            assertThat(observed).containsAll(expected);
        } finally {
            redis.delete(expected);
        }
    }
}
