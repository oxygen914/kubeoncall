package com.kubeoncall.rag;

import java.util.Map;

/** Safe provider failure surfaced in RAG diagnostics without endpoints, payloads, or credentials. */
public class RagProviderException extends RuntimeException {

    public RagProviderException(String message) {
        super(message);
    }

    public static RagProviderException fromResponse(String provider, Map<String, Object> response) {
        Object errorType = response == null ? null : response.get("errorType");
        Object httpStatus = response == null ? null : response.get("httpStatus");
        return new RagProviderException(provider
                + " provider request failed: errorType="
                + (errorType == null ? "unknown" : errorType)
                + ", httpStatus="
                + (httpStatus == null ? "unknown" : httpStatus));
    }
}
