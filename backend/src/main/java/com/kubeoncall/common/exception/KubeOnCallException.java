package com.kubeoncall.common.exception;

public class KubeOnCallException extends RuntimeException {

    public KubeOnCallException(String message) {
        super(message);
    }

    public KubeOnCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
