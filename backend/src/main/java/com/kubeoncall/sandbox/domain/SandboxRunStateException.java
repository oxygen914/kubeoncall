package com.kubeoncall.sandbox.domain;

/**
 * Raised when a sandbox run or cleanup status transition is attempted that the state machine does
 * not permit — including any attempt to move a terminal state. Kept in the domain package so the
 * run lifecycle code does not depend on the web layer; the v1 exception handler maps it to the
 * unified {@code {error, meta}} envelope with a stable {@code 409} code.
 */
public class SandboxRunStateException extends RuntimeException {

    public SandboxRunStateException(String message) {
        super(message);
    }
}
