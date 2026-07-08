package com.kubeoncall.rag;

import com.kubeoncall.common.config.KubeOnCallProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmbeddingServiceTest {

    @Test
    void mockEmbeddingShouldBeDeterministic() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setMockEmbeddingEnabled(true);
        properties.getRag().setEmbeddingDimensions(6);
        EmbeddingService service = new EmbeddingService(List.of(), properties);

        EmbeddingService.EmbeddingResult first = service.embed("payment timeout");
        EmbeddingService.EmbeddingResult second = service.embed("payment timeout");

        assertEquals(first.vector(), second.vector());
        assertEquals(6, first.vector().size());
        assertEquals("deterministic_mock", first.provider());
        assertEquals(true, first.mock());
    }

    @Test
    void disabledEmbeddingShouldFailFast() {
        EmbeddingService service = new EmbeddingService(List.of(), new KubeOnCallProperties());

        assertThrows(IllegalStateException.class, () -> service.embed("payment timeout"));
    }
}
