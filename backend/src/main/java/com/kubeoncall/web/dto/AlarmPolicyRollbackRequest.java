package com.kubeoncall.web.dto;

/** Selects a previously loaded policy snapshot as the active policy version. */
public record AlarmPolicyRollbackRequest(String version) {}
