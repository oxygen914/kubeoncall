package com.kubeoncall.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class MigrationLeaseTest {

    @Test
    void releasesOnlyWhenLuaScriptConfirmsHolderToken() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), eq(List.of("migration:lock")), eq("holder-a")))
                .thenReturn(1L);

        assertThat(MigrationLease.releaseIfOwned(redis, "migration:lock", "holder-a"))
                .isTrue();
        verify(redis).execute(any(RedisScript.class), eq(List.of("migration:lock")), eq("holder-a"));
    }

    @Test
    void doesNotReportReleaseWhenTheLeaseBelongsToAnotherRunner() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), eq(List.of("migration:lock")), eq("holder-a")))
                .thenReturn(0L);

        assertThat(MigrationLease.releaseIfOwned(redis, "migration:lock", "holder-a"))
                .isFalse();
    }

    @Test
    void renewsOnlyWhenLuaScriptConfirmsHolderToken() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), eq(List.of("migration:lock")), eq("holder-a"), any(String.class)))
                .thenReturn(1L);

        assertThat(MigrationLease.renewIfOwned(redis, "migration:lock", "holder-a"))
                .isTrue();
    }
}
