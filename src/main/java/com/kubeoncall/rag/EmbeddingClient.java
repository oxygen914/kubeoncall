package com.kubeoncall.rag;

import java.util.List;

public interface EmbeddingClient {

    boolean available();

    List<Double> embed(String text);

    default String provider() {
        return getClass().getSimpleName();
    }
}
