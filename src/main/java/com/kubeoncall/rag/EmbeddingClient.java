package com.kubeoncall.rag;

import java.util.List;

public interface EmbeddingClient {

    boolean available();

    List<Double> embed(String text);

    default List<List<Double>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        return texts.stream().map(this::embed).toList();
    }

    default String provider() {
        return getClass().getSimpleName();
    }
}
