package com.kubeoncall.common.exception;

/** Stable classification for failures returned by remote HTTP clients. */
public enum RemoteCallFailureKind {
    RATE_LIMITED,
    TIMEOUT,
    REMOTE_ERROR
}
