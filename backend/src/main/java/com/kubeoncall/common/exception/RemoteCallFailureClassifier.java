package com.kubeoncall.common.exception;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Keeps transport-specific exception handling outside core business domains.
 *
 * <p>The returned value is deliberately bounded and contains no provider response body.
 */
public final class RemoteCallFailureClassifier {

    private RemoteCallFailureClassifier() {}

    public static RemoteCallFailureKind classify(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof RestClientResponseException responseException) {
                return responseException.getStatusCode().value() == 429
                        ? RemoteCallFailureKind.RATE_LIMITED
                        : RemoteCallFailureKind.REMOTE_ERROR;
            }
            if (current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof TimeoutException
                    || current instanceof ResourceAccessException) {
                return RemoteCallFailureKind.TIMEOUT;
            }
            current = current.getCause();
        }
        return RemoteCallFailureKind.REMOTE_ERROR;
    }
}
