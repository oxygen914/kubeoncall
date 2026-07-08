package com.kubeoncall.alarm.domain;

/**
 * Lifecycle status of a normalized alarm event.
 *
 * <p>{@code FIRING} is the active state; {@code RESOLVED} is the recovery confirmation;
 * {@code SUPPRESSED} means the event was intentionally de-noised (e.g. by a suppression rule or a
 * maintenance window) but is still auditable.
 */
public enum AlarmStatus {
    FIRING,
    RESOLVED,
    SUPPRESSED
}
