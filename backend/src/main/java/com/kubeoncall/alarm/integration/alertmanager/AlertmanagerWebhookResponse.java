package com.kubeoncall.alarm.integration.alertmanager;

/** Result returned only after every accepted alert is durably written to the inbox. */
public record AlertmanagerWebhookResponse(int accepted, int duplicates, int rejected, String batchId) {}
