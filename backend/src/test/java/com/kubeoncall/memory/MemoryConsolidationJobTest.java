package com.kubeoncall.memory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;

class MemoryConsolidationJobTest {

    @Test
    void shouldRunOnlyWhenEnabled() {
        MemoryConsolidationService service = mock(MemoryConsolidationService.class);
        RedisLeaseLock lock = mock(RedisLeaseLock.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getMemory().setConsolidationScanLimit(123);
        when(lock.tryAcquire(any(), any(), any())).thenReturn(true);
        MemoryConsolidationJob job = new MemoryConsolidationJob(service, properties, lock);

        job.consolidate();
        verify(service, never()).consolidate(any(), anyInt(), anyBoolean());

        properties.getMemory().setConsolidationEnabled(true);
        job.consolidate();
        verify(service)
                .consolidate(any(), org.mockito.ArgumentMatchers.eq(123), org.mockito.ArgumentMatchers.eq(false));
        verify(lock).release(org.mockito.ArgumentMatchers.eq("memory-consolidation:lock"), any());
    }

    @Test
    void shouldSkipWhenAnotherInstanceOwnsLease() {
        MemoryConsolidationService service = mock(MemoryConsolidationService.class);
        RedisLeaseLock lock = mock(RedisLeaseLock.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getMemory().setConsolidationEnabled(true);
        when(lock.tryAcquire(org.mockito.ArgumentMatchers.eq("memory-consolidation:lock"), any(), any()))
                .thenReturn(false);
        MemoryConsolidationJob job = new MemoryConsolidationJob(service, properties, lock);

        job.consolidate();

        verify(service, never()).consolidate(any(), anyInt(), anyBoolean());
        verify(lock, never()).release(any(), any());
    }
}
