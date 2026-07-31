package com.kubeoncall.tool;

/** A verifier stage exposed to clients as a read-only capability. */
public record VerifierCapability(String node, String type, String description) {}
