package com.kubeoncall.web.dto;

/** Stable machine-readable codes for failed HTTP requests. */
public enum ApiErrorCode {
    INVALID_REQUEST,
    MALFORMED_REQUEST,
    NOT_FOUND,
    CONFLICT,
    APPROVAL_REQUIRED,
    REPLAN_REQUIRED,
    BUSINESS_ERROR,
    INTERNAL_ERROR
}
