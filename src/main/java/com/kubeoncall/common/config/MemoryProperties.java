package com.kubeoncall.common.config;

class MemoryProperties {

    private boolean enabled = true;
    private long sessionTtlSeconds = 86400;
    private int maxSessionTurns = 12;
    private int sessionRecentTurns = 6;
    private int sessionTokenBudget = 1200;
    private int sessionAppendMaxRetries = 5;
    private int sessionSummaryTokenBudget = 600;
    private int contextTokenBudget = 2400;
    private int observationTokenBudget = 800;
    private int maxObservationEntries = 20;
    private boolean longTermEnabled = true;
    private int injectMaxEntries = 5;
    private int staleAfterDays = 30;
    private String temporalNormalizationZone = "Asia/Shanghai";
    private boolean consolidationEnabled = false;
    private String consolidationCron = "0 0 3 * * *";
    private int consolidationScanLimit = 500;
    private long consolidationLockTtlSeconds = 1800;
    private double duplicateSimilarityThreshold = 0.92d;
    private int extractionMaxAttempts = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getSessionTtlSeconds() {
        return sessionTtlSeconds;
    }

    public void setSessionTtlSeconds(long sessionTtlSeconds) {
        this.sessionTtlSeconds = sessionTtlSeconds;
    }

    public int getMaxSessionTurns() {
        return maxSessionTurns;
    }

    public void setMaxSessionTurns(int maxSessionTurns) {
        this.maxSessionTurns = maxSessionTurns;
    }

    public int getSessionRecentTurns() {
        return sessionRecentTurns;
    }

    public void setSessionRecentTurns(int sessionRecentTurns) {
        this.sessionRecentTurns = sessionRecentTurns;
    }

    public int getSessionTokenBudget() {
        return sessionTokenBudget;
    }

    public void setSessionTokenBudget(int sessionTokenBudget) {
        this.sessionTokenBudget = sessionTokenBudget;
    }

    public int getSessionAppendMaxRetries() {
        return sessionAppendMaxRetries;
    }

    public void setSessionAppendMaxRetries(int sessionAppendMaxRetries) {
        this.sessionAppendMaxRetries = sessionAppendMaxRetries;
    }

    public int getSessionSummaryTokenBudget() {
        return sessionSummaryTokenBudget;
    }

    public void setSessionSummaryTokenBudget(int sessionSummaryTokenBudget) {
        this.sessionSummaryTokenBudget = sessionSummaryTokenBudget;
    }

    public int getContextTokenBudget() {
        return contextTokenBudget;
    }

    public void setContextTokenBudget(int contextTokenBudget) {
        this.contextTokenBudget = contextTokenBudget;
    }

    public int getObservationTokenBudget() {
        return observationTokenBudget;
    }

    public void setObservationTokenBudget(int observationTokenBudget) {
        this.observationTokenBudget = observationTokenBudget;
    }

    public int getMaxObservationEntries() {
        return maxObservationEntries;
    }

    public void setMaxObservationEntries(int maxObservationEntries) {
        this.maxObservationEntries = maxObservationEntries;
    }

    public boolean isLongTermEnabled() {
        return longTermEnabled;
    }

    public void setLongTermEnabled(boolean longTermEnabled) {
        this.longTermEnabled = longTermEnabled;
    }

    public int getInjectMaxEntries() {
        return injectMaxEntries;
    }

    public void setInjectMaxEntries(int injectMaxEntries) {
        this.injectMaxEntries = injectMaxEntries;
    }

    public int getStaleAfterDays() {
        return staleAfterDays;
    }

    public void setStaleAfterDays(int staleAfterDays) {
        this.staleAfterDays = staleAfterDays;
    }

    public String getTemporalNormalizationZone() {
        return temporalNormalizationZone;
    }

    public void setTemporalNormalizationZone(String temporalNormalizationZone) {
        this.temporalNormalizationZone = temporalNormalizationZone;
    }

    public boolean isConsolidationEnabled() {
        return consolidationEnabled;
    }

    public void setConsolidationEnabled(boolean consolidationEnabled) {
        this.consolidationEnabled = consolidationEnabled;
    }

    public String getConsolidationCron() {
        return consolidationCron;
    }

    public void setConsolidationCron(String consolidationCron) {
        this.consolidationCron = consolidationCron;
    }

    public int getConsolidationScanLimit() {
        return consolidationScanLimit;
    }

    public void setConsolidationScanLimit(int consolidationScanLimit) {
        this.consolidationScanLimit = consolidationScanLimit;
    }

    public long getConsolidationLockTtlSeconds() {
        return consolidationLockTtlSeconds;
    }

    public void setConsolidationLockTtlSeconds(long consolidationLockTtlSeconds) {
        this.consolidationLockTtlSeconds = consolidationLockTtlSeconds;
    }

    public double getDuplicateSimilarityThreshold() {
        return duplicateSimilarityThreshold;
    }

    public void setDuplicateSimilarityThreshold(double duplicateSimilarityThreshold) {
        this.duplicateSimilarityThreshold = duplicateSimilarityThreshold;
    }

    public int getExtractionMaxAttempts() {
        return extractionMaxAttempts;
    }

    public void setExtractionMaxAttempts(int extractionMaxAttempts) {
        this.extractionMaxAttempts = extractionMaxAttempts;
    }
}
