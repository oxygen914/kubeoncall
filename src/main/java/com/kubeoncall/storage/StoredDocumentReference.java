package com.kubeoncall.storage;

public record StoredDocumentReference(String objectKey, String bucket, boolean stored, String message) {}
