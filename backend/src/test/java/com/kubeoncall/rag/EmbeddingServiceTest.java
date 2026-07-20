package com.kubeoncall.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;

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

    @Test
    void shouldRejectProviderVectorWithUnexpectedDimensions() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setEmbeddingEnabled(true);
        properties.getRag().setEmbeddingDimensions(3);
        EmbeddingClient client = new EmbeddingClient() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public List<Double> embed(String text) {
                return List.of(0.1, 0.2);
            }

            @Override
            public String provider() {
                return "dimension-mismatch";
            }
        };
        EmbeddingService service = new EmbeddingService(List.of(client), properties);

        assertThrows(IllegalStateException.class, () -> service.embed("payment timeout"));
    }
}
