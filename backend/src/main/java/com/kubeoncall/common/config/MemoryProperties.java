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
    private int unifiedContextTokenBudget = 3200;
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
    private boolean semanticDuplicateEnabled = false;
    private double semanticDuplicateWeight = 0.75d;
    private int extractionMaxAttempts = 3;
    private long extractionProcessingTimeoutSeconds = 300;
    private int extractionReclaimLimit = 100;
    private long extractionStatusTtlSeconds = 86400;
    private boolean llmExtractionEnabled = false;
    private String llmExtractionEndpoint;
    private String llmExtractionApiKey;
    private String llmExtractionModel = "";
    private int llmExtractionTimeoutMillis = 5000;
    private double extractionQualityThreshold = 0.55d;
    private boolean tokenizerEnabled = false;
    private String tokenizerEndpoint;
    private String tokenizerApiKey;
    private String tokenizerModel = "";
    private int tokenizerTimeoutMillis = 2000;
    private String tokenizerMode = "count_endpoint";
    private int tokenizerChatOverheadTokens = -1;

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

    public int getUnifiedContextTokenBudget() {
        return unifiedContextTokenBudget;
    }

    public void setUnifiedContextTokenBudget(int unifiedContextTokenBudget) {
        this.unifiedContextTokenBudget = unifiedContextTokenBudget;
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

    public boolean isSemanticDuplicateEnabled() {
        return semanticDuplicateEnabled;
    }

    public void setSemanticDuplicateEnabled(boolean semanticDuplicateEnabled) {
        this.semanticDuplicateEnabled = semanticDuplicateEnabled;
    }

    public double getSemanticDuplicateWeight() {
        return semanticDuplicateWeight;
    }

    public void setSemanticDuplicateWeight(double semanticDuplicateWeight) {
        this.semanticDuplicateWeight = semanticDuplicateWeight;
    }

    public int getExtractionMaxAttempts() {
        return extractionMaxAttempts;
    }

    public void setExtractionMaxAttempts(int extractionMaxAttempts) {
        this.extractionMaxAttempts = extractionMaxAttempts;
    }

    public long getExtractionProcessingTimeoutSeconds() {
        return extractionProcessingTimeoutSeconds;
    }

    public void setExtractionProcessingTimeoutSeconds(long extractionProcessingTimeoutSeconds) {
        this.extractionProcessingTimeoutSeconds = extractionProcessingTimeoutSeconds;
    }

    public int getExtractionReclaimLimit() {
        return extractionReclaimLimit;
    }

    public void setExtractionReclaimLimit(int extractionReclaimLimit) {
        this.extractionReclaimLimit = extractionReclaimLimit;
    }

    public long getExtractionStatusTtlSeconds() {
        return extractionStatusTtlSeconds;
    }

    public void setExtractionStatusTtlSeconds(long extractionStatusTtlSeconds) {
        this.extractionStatusTtlSeconds = extractionStatusTtlSeconds;
    }

    public boolean isLlmExtractionEnabled() {
        return llmExtractionEnabled;
    }

    public void setLlmExtractionEnabled(boolean llmExtractionEnabled) {
        this.llmExtractionEnabled = llmExtractionEnabled;
    }

    public String getLlmExtractionEndpoint() {
        return llmExtractionEndpoint;
    }

    public void setLlmExtractionEndpoint(String llmExtractionEndpoint) {
        this.llmExtractionEndpoint = llmExtractionEndpoint;
    }

    public String getLlmExtractionApiKey() {
        return llmExtractionApiKey;
    }

    public void setLlmExtractionApiKey(String llmExtractionApiKey) {
        this.llmExtractionApiKey = llmExtractionApiKey;
    }

    public String getLlmExtractionModel() {
        return llmExtractionModel;
    }

    public void setLlmExtractionModel(String llmExtractionModel) {
        this.llmExtractionModel = llmExtractionModel;
    }

    public int getLlmExtractionTimeoutMillis() {
        return llmExtractionTimeoutMillis;
    }

    public void setLlmExtractionTimeoutMillis(int llmExtractionTimeoutMillis) {
        this.llmExtractionTimeoutMillis = llmExtractionTimeoutMillis;
    }

    public double getExtractionQualityThreshold() {
        return extractionQualityThreshold;
    }

    public void setExtractionQualityThreshold(double extractionQualityThreshold) {
        this.extractionQualityThreshold = extractionQualityThreshold;
    }

    public boolean isTokenizerEnabled() {
        return tokenizerEnabled;
    }

    public void setTokenizerEnabled(boolean tokenizerEnabled) {
        this.tokenizerEnabled = tokenizerEnabled;
    }

    public String getTokenizerEndpoint() {
        return tokenizerEndpoint;
    }

    public void setTokenizerEndpoint(String tokenizerEndpoint) {
        this.tokenizerEndpoint = tokenizerEndpoint;
    }

    public String getTokenizerApiKey() {
        return tokenizerApiKey;
    }

    public void setTokenizerApiKey(String tokenizerApiKey) {
        this.tokenizerApiKey = tokenizerApiKey;
    }

    public String getTokenizerModel() {
        return tokenizerModel;
    }

    public void setTokenizerModel(String tokenizerModel) {
        this.tokenizerModel = tokenizerModel;
    }

    public int getTokenizerTimeoutMillis() {
        return tokenizerTimeoutMillis;
    }

    public void setTokenizerTimeoutMillis(int tokenizerTimeoutMillis) {
        this.tokenizerTimeoutMillis = tokenizerTimeoutMillis;
    }

    public String getTokenizerMode() {
        return tokenizerMode;
    }

    public void setTokenizerMode(String tokenizerMode) {
        this.tokenizerMode = tokenizerMode;
    }

    public int getTokenizerChatOverheadTokens() {
        return tokenizerChatOverheadTokens;
    }

    public void setTokenizerChatOverheadTokens(int tokenizerChatOverheadTokens) {
        this.tokenizerChatOverheadTokens = tokenizerChatOverheadTokens;
    }
}
