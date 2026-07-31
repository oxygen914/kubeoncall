package com.kubeoncall.identity;

/** Built-in role codes. Custom roles (P1) extend this set; the three here are seeded by
 * {@code V1__identity.sql} and cannot be deleted. */
public enum BuiltInRole {
    VIEWER,
    OPERATOR,
    ADMIN
}
