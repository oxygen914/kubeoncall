package com.kubeoncall.migration;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.async.RedisAsyncCommands;

/** Executes Redis SCAN with an explicit opaque cursor so a persisted checkpoint can resume it. */
final class RedisCursorScanner {

    private RedisCursorScanner() {}

    static Page scan(StringRedisTemplate redis, String cursor, String pattern, int count) {
        if (redis == null) {
            throw new IllegalStateException("Redis unavailable for checkpointed SCAN");
        }
        String safeCursor = cursor == null || cursor.isBlank() ? "0" : cursor.trim();
        return redis.execute((RedisCallback<Page>) connection -> {
            Object nativeConnection = connection.getNativeConnection();
            if (!(nativeConnection instanceof RedisAsyncCommands<?, ?> rawCommands)) {
                String type = nativeConnection == null
                        ? "null"
                        : nativeConnection.getClass().getName();
                throw new IllegalStateException("Redis connection does not expose a Lettuce cursor client: " + type);
            }
            @SuppressWarnings("unchecked")
            RedisAsyncCommands<byte[], byte[]> lettuce = (RedisAsyncCommands<byte[], byte[]>) rawCommands;
            KeyScanCursor<byte[]> page = lettuce.scan(
                            ScanCursor.of(safeCursor),
                            new ScanArgs().match(pattern).limit(Math.max(1, count)))
                    .toCompletableFuture()
                    .join();
            List<String> keys = page.getKeys().stream()
                    .map(value -> new String(value, StandardCharsets.UTF_8))
                    .toList();
            return new Page(page.getCursor(), keys);
        });
    }

    record Page(String nextCursor, List<String> keys) {

        Page {
            nextCursor = nextCursor == null || nextCursor.isBlank() ? "0" : nextCursor;
            keys = List.copyOf(keys == null ? List.of() : keys);
        }

        boolean completed() {
            return "0".equals(nextCursor);
        }
    }
}
