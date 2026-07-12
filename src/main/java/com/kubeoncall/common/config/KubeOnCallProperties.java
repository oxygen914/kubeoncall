package com.kubeoncall.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kubeoncall")
public class KubeOnCallProperties {

    private final Agent agent = new Agent();
    private final Approval approval = new Approval();
    private final Rag rag = new Rag();
    private final Storage storage = new Storage();
    private final Mcp mcp = new Mcp();
    private final Workflow workflow = new Workflow();
    private final Audit audit = new Audit();
    private final Integrations integrations = new Integrations();
    private final Alarm alarm = new Alarm();
    private final Memory memory = new Memory();
    private final Skill skill = new Skill();

    public Agent getAgent() {
        return agent;
    }

    public Approval getApproval() {
        return approval;
    }

    public Rag getRag() {
        return rag;
    }

    public Storage getStorage() {
        return storage;
    }

    public Mcp getMcp() {
        return mcp;
    }

    public Workflow getWorkflow() {
        return workflow;
    }

    public Integrations getIntegrations() {
        return integrations;
    }

    public Audit getAudit() {
        return audit;
    }

    public Alarm getAlarm() {
        return alarm;
    }

    public Memory getMemory() {
        return memory;
    }

    public Skill getSkill() {
        return skill;
    }

    public static class Agent {
        private int maxLoops = 3;
        private boolean plannerLlmEnabled = true;

        public int getMaxLoops() {
            return maxLoops;
        }

        public void setMaxLoops(int maxLoops) {
            this.maxLoops = maxLoops;
        }

        public boolean isPlannerLlmEnabled() {
            return plannerLlmEnabled;
        }

        public void setPlannerLlmEnabled(boolean plannerLlmEnabled) {
            this.plannerLlmEnabled = plannerLlmEnabled;
        }
    }

    public static class Approval {
        private boolean enabled = true;
        private int callbackTimeoutSeconds = 1800;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getCallbackTimeoutSeconds() {
            return callbackTimeoutSeconds;
        }

        public void setCallbackTimeoutSeconds(int callbackTimeoutSeconds) {
            this.callbackTimeoutSeconds = callbackTimeoutSeconds;
        }
    }

    public static class Rag {
        private int defaultTopK = 5;
        private String knowledgeIndex = "kubeoncall-knowledge";
        private String knowledgeIndexAlias = "";
        private boolean vectorEnabled = false;
        private boolean crossEncoderEnabled = false;
        private String crossEncoderEndpoint;
        private String crossEncoderModel = "cross-encoder";
        private int crossEncoderTimeoutMillis = 3000;
        private int lexicalCandidateTopN = 20;
        private String vectorEndpoint;
        private int vectorTimeoutMillis = 3000;
        private int vectorCandidateTopN = 20;
        private String vectorBackend = "es";
        private boolean esKnnEnabled = false;
        private boolean embeddingEnabled = false;
        private boolean mockEmbeddingEnabled = false;
        private String embeddingEndpoint;
        private String embeddingApiKey;
        private String embeddingModel = "text-embedding-3-small";
        private String embeddingVersion = "v1";
        private int embeddingTimeoutMillis = 3000;
        private int embeddingDimensions = 1536;
        private String chunkStrategy = "recursive";
        private int chunkSize = 280;
        private int chunkOverlap = 0;
        private int rrfK = 60;
        private int rerankTopN = 30;
        private String runbookLocation = "classpath*:runbooks/*.md";
        private boolean runbookBootstrapEnabled = false;

        public int getDefaultTopK() {
            return defaultTopK;
        }

        public void setDefaultTopK(int defaultTopK) {
            this.defaultTopK = defaultTopK;
        }

        public String getKnowledgeIndex() {
            return knowledgeIndex;
        }

        public void setKnowledgeIndex(String knowledgeIndex) {
            this.knowledgeIndex = knowledgeIndex;
        }

        public String getKnowledgeIndexAlias() {
            return knowledgeIndexAlias;
        }

        public void setKnowledgeIndexAlias(String knowledgeIndexAlias) {
            this.knowledgeIndexAlias = knowledgeIndexAlias;
        }

        public boolean isVectorEnabled() {
            return vectorEnabled;
        }

        public void setVectorEnabled(boolean vectorEnabled) {
            this.vectorEnabled = vectorEnabled;
        }

        public boolean isCrossEncoderEnabled() {
            return crossEncoderEnabled;
        }

        public void setCrossEncoderEnabled(boolean crossEncoderEnabled) {
            this.crossEncoderEnabled = crossEncoderEnabled;
        }

        public String getCrossEncoderEndpoint() {
            return crossEncoderEndpoint;
        }

        public void setCrossEncoderEndpoint(String crossEncoderEndpoint) {
            this.crossEncoderEndpoint = crossEncoderEndpoint;
        }

        public String getCrossEncoderModel() {
            return crossEncoderModel;
        }

        public void setCrossEncoderModel(String crossEncoderModel) {
            this.crossEncoderModel = crossEncoderModel;
        }

        public int getCrossEncoderTimeoutMillis() {
            return crossEncoderTimeoutMillis;
        }

        public void setCrossEncoderTimeoutMillis(int crossEncoderTimeoutMillis) {
            this.crossEncoderTimeoutMillis = crossEncoderTimeoutMillis;
        }

        public int getLexicalCandidateTopN() {
            return lexicalCandidateTopN;
        }

        public void setLexicalCandidateTopN(int lexicalCandidateTopN) {
            this.lexicalCandidateTopN = lexicalCandidateTopN;
        }

        public String getVectorEndpoint() {
            return vectorEndpoint;
        }

        public void setVectorEndpoint(String vectorEndpoint) {
            this.vectorEndpoint = vectorEndpoint;
        }

        public int getVectorTimeoutMillis() {
            return vectorTimeoutMillis;
        }

        public void setVectorTimeoutMillis(int vectorTimeoutMillis) {
            this.vectorTimeoutMillis = vectorTimeoutMillis;
        }

        public int getVectorCandidateTopN() {
            return vectorCandidateTopN;
        }

        public void setVectorCandidateTopN(int vectorCandidateTopN) {
            this.vectorCandidateTopN = vectorCandidateTopN;
        }

        public String getVectorBackend() {
            return vectorBackend;
        }

        public void setVectorBackend(String vectorBackend) {
            this.vectorBackend = vectorBackend;
        }

        public boolean isEsKnnEnabled() {
            return esKnnEnabled;
        }

        public void setEsKnnEnabled(boolean esKnnEnabled) {
            this.esKnnEnabled = esKnnEnabled;
        }

        public boolean isEmbeddingEnabled() {
            return embeddingEnabled;
        }

        public void setEmbeddingEnabled(boolean embeddingEnabled) {
            this.embeddingEnabled = embeddingEnabled;
        }

        public boolean isMockEmbeddingEnabled() {
            return mockEmbeddingEnabled;
        }

        public void setMockEmbeddingEnabled(boolean mockEmbeddingEnabled) {
            this.mockEmbeddingEnabled = mockEmbeddingEnabled;
        }

        public String getEmbeddingEndpoint() {
            return embeddingEndpoint;
        }

        public void setEmbeddingEndpoint(String embeddingEndpoint) {
            this.embeddingEndpoint = embeddingEndpoint;
        }

        public String getEmbeddingApiKey() {
            return embeddingApiKey;
        }

        public void setEmbeddingApiKey(String embeddingApiKey) {
            this.embeddingApiKey = embeddingApiKey;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        public String getEmbeddingVersion() {
            return embeddingVersion;
        }

        public void setEmbeddingVersion(String embeddingVersion) {
            this.embeddingVersion = embeddingVersion;
        }

        public int getEmbeddingTimeoutMillis() {
            return embeddingTimeoutMillis;
        }

        public void setEmbeddingTimeoutMillis(int embeddingTimeoutMillis) {
            this.embeddingTimeoutMillis = embeddingTimeoutMillis;
        }

        public int getEmbeddingDimensions() {
            return embeddingDimensions;
        }

        public void setEmbeddingDimensions(int embeddingDimensions) {
            this.embeddingDimensions = embeddingDimensions;
        }

        public String getChunkStrategy() {
            return chunkStrategy;
        }

        public void setChunkStrategy(String chunkStrategy) {
            this.chunkStrategy = chunkStrategy;
        }

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        public int getChunkOverlap() {
            return chunkOverlap;
        }

        public void setChunkOverlap(int chunkOverlap) {
            this.chunkOverlap = chunkOverlap;
        }

        public int getRrfK() {
            return rrfK;
        }

        public void setRrfK(int rrfK) {
            this.rrfK = rrfK;
        }

        public int getRerankTopN() {
            return rerankTopN;
        }

        public void setRerankTopN(int rerankTopN) {
            this.rerankTopN = rerankTopN;
        }

        public String getRunbookLocation() {
            return runbookLocation;
        }

        public void setRunbookLocation(String runbookLocation) {
            this.runbookLocation = runbookLocation;
        }

        public boolean isRunbookBootstrapEnabled() {
            return runbookBootstrapEnabled;
        }

        public void setRunbookBootstrapEnabled(boolean runbookBootstrapEnabled) {
            this.runbookBootstrapEnabled = runbookBootstrapEnabled;
        }
    }

    public static class Storage {
        private final Minio minio = new Minio();

        public Minio getMinio() {
            return minio;
        }

        public static class Minio {
            private String endpoint;
            private String accessKey;
            private String secretKey;
            private String bucket;

            public String getEndpoint() {
                return endpoint;
            }

            public void setEndpoint(String endpoint) {
                this.endpoint = endpoint;
            }

            public String getAccessKey() {
                return accessKey;
            }

            public void setAccessKey(String accessKey) {
                this.accessKey = accessKey;
            }

            public String getSecretKey() {
                return secretKey;
            }

            public void setSecretKey(String secretKey) {
                this.secretKey = secretKey;
            }

            public String getBucket() {
                return bucket;
            }

            public void setBucket(String bucket) {
                this.bucket = bucket;
            }
        }
    }

    public static class Mcp {
        private boolean enabled = true;
        private String serverName = "local-mcp";
        private String endpoint = "http://localhost:18080/mcp/call";
        private int timeoutMillis = 3000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getServerName() {
            return serverName;
        }

        public void setServerName(String serverName) {
            this.serverName = serverName;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public int getTimeoutMillis() {
            return timeoutMillis;
        }

        public void setTimeoutMillis(int timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }
    }

    public static class Workflow {
        private long alarmDedupTtlSeconds = 600;
        private long nodeTimeoutMillis = 3000;

        public long getAlarmDedupTtlSeconds() {
            return alarmDedupTtlSeconds;
        }

        public void setAlarmDedupTtlSeconds(long alarmDedupTtlSeconds) {
            this.alarmDedupTtlSeconds = alarmDedupTtlSeconds;
        }

        public long getNodeTimeoutMillis() {
            return nodeTimeoutMillis;
        }

        public void setNodeTimeoutMillis(long nodeTimeoutMillis) {
            this.nodeTimeoutMillis = nodeTimeoutMillis;
        }
    }

    public static class Audit {
        private String repository = "redis";
        private int retentionHours = 168;

        public String getRepository() {
            return repository;
        }

        public void setRepository(String repository) {
            this.repository = repository;
        }

        public int getRetentionHours() {
            return retentionHours;
        }

        public void setRetentionHours(int retentionHours) {
            this.retentionHours = retentionHours;
        }
    }

    public static class Alarm {
        private boolean enabled = true;
        private String policyLocation = "classpath:alarm-policies.yml";
        private String defaultSeverity = "P3";
        private long activeTtlSeconds = 86400;
        private long resolvedRetentionSeconds = 3600;
        private long p0DedupTtlSeconds = 1800;
        private long p1DedupTtlSeconds = 1200;
        private long p2DedupTtlSeconds = 600;
        private long p3DedupTtlSeconds = 300;
        private long infoDedupTtlSeconds = 120;
        private long nodeNotReadySuppressionTtlSeconds = 1800;
        private long escalationTtlSeconds = 3600;
        private long acknowledgementTtlSeconds = 86400;
        private long silenceApprovalTtlSeconds = 1800;
        private long p0RecoveryWindowSeconds = 900;
        private long p1RecoveryWindowSeconds = 600;
        private long p2RecoveryWindowSeconds = 300;
        private long p3RecoveryWindowSeconds = 120;
        private long infoRecoveryWindowSeconds = 0;
        private long recoveryStateTtlSeconds = 259200;
        private int recoveryBatchSize = 100;
        private boolean recoveryHealthCheckRequired = true;
        private String recoveryHealthCheckEndpoint;
        private int recoveryHealthCheckTimeoutMillis = 3000;
        private long recoveryConfirmationTimeoutSeconds = 86400;
        private long p0EscalationCount = 2;
        private long p1EscalationCount = 3;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPolicyLocation() {
            return policyLocation;
        }

        public void setPolicyLocation(String policyLocation) {
            this.policyLocation = policyLocation;
        }

        public String getDefaultSeverity() {
            return defaultSeverity;
        }

        public void setDefaultSeverity(String defaultSeverity) {
            this.defaultSeverity = defaultSeverity;
        }

        public long getActiveTtlSeconds() {
            return activeTtlSeconds;
        }

        public void setActiveTtlSeconds(long activeTtlSeconds) {
            this.activeTtlSeconds = activeTtlSeconds;
        }

        public long getResolvedRetentionSeconds() {
            return resolvedRetentionSeconds;
        }

        public void setResolvedRetentionSeconds(long resolvedRetentionSeconds) {
            this.resolvedRetentionSeconds = resolvedRetentionSeconds;
        }

        public long getP0DedupTtlSeconds() {
            return p0DedupTtlSeconds;
        }

        public void setP0DedupTtlSeconds(long p0DedupTtlSeconds) {
            this.p0DedupTtlSeconds = p0DedupTtlSeconds;
        }

        public long getP1DedupTtlSeconds() {
            return p1DedupTtlSeconds;
        }

        public void setP1DedupTtlSeconds(long p1DedupTtlSeconds) {
            this.p1DedupTtlSeconds = p1DedupTtlSeconds;
        }

        public long getP2DedupTtlSeconds() {
            return p2DedupTtlSeconds;
        }

        public void setP2DedupTtlSeconds(long p2DedupTtlSeconds) {
            this.p2DedupTtlSeconds = p2DedupTtlSeconds;
        }

        public long getP3DedupTtlSeconds() {
            return p3DedupTtlSeconds;
        }

        public void setP3DedupTtlSeconds(long p3DedupTtlSeconds) {
            this.p3DedupTtlSeconds = p3DedupTtlSeconds;
        }

        public long getInfoDedupTtlSeconds() {
            return infoDedupTtlSeconds;
        }

        public void setInfoDedupTtlSeconds(long infoDedupTtlSeconds) {
            this.infoDedupTtlSeconds = infoDedupTtlSeconds;
        }

        public long getNodeNotReadySuppressionTtlSeconds() {
            return nodeNotReadySuppressionTtlSeconds;
        }

        public void setNodeNotReadySuppressionTtlSeconds(long nodeNotReadySuppressionTtlSeconds) {
            this.nodeNotReadySuppressionTtlSeconds = nodeNotReadySuppressionTtlSeconds;
        }

        public long getEscalationTtlSeconds() {
            return escalationTtlSeconds;
        }

        public void setEscalationTtlSeconds(long escalationTtlSeconds) {
            this.escalationTtlSeconds = escalationTtlSeconds;
        }

        public long getAcknowledgementTtlSeconds() {
            return acknowledgementTtlSeconds;
        }

        public void setAcknowledgementTtlSeconds(long acknowledgementTtlSeconds) {
            this.acknowledgementTtlSeconds = acknowledgementTtlSeconds;
        }

        public long getSilenceApprovalTtlSeconds() {
            return silenceApprovalTtlSeconds;
        }

        public void setSilenceApprovalTtlSeconds(long silenceApprovalTtlSeconds) {
            this.silenceApprovalTtlSeconds = silenceApprovalTtlSeconds;
        }

        public long getP0RecoveryWindowSeconds() {
            return p0RecoveryWindowSeconds;
        }

        public void setP0RecoveryWindowSeconds(long p0RecoveryWindowSeconds) {
            this.p0RecoveryWindowSeconds = p0RecoveryWindowSeconds;
        }

        public long getP1RecoveryWindowSeconds() {
            return p1RecoveryWindowSeconds;
        }

        public void setP1RecoveryWindowSeconds(long p1RecoveryWindowSeconds) {
            this.p1RecoveryWindowSeconds = p1RecoveryWindowSeconds;
        }

        public long getP2RecoveryWindowSeconds() {
            return p2RecoveryWindowSeconds;
        }

        public void setP2RecoveryWindowSeconds(long p2RecoveryWindowSeconds) {
            this.p2RecoveryWindowSeconds = p2RecoveryWindowSeconds;
        }

        public long getP3RecoveryWindowSeconds() {
            return p3RecoveryWindowSeconds;
        }

        public void setP3RecoveryWindowSeconds(long p3RecoveryWindowSeconds) {
            this.p3RecoveryWindowSeconds = p3RecoveryWindowSeconds;
        }

        public long getInfoRecoveryWindowSeconds() {
            return infoRecoveryWindowSeconds;
        }

        public void setInfoRecoveryWindowSeconds(long infoRecoveryWindowSeconds) {
            this.infoRecoveryWindowSeconds = infoRecoveryWindowSeconds;
        }

        public long getRecoveryStateTtlSeconds() {
            return recoveryStateTtlSeconds;
        }

        public void setRecoveryStateTtlSeconds(long recoveryStateTtlSeconds) {
            this.recoveryStateTtlSeconds = recoveryStateTtlSeconds;
        }

        public int getRecoveryBatchSize() {
            return recoveryBatchSize;
        }

        public void setRecoveryBatchSize(int recoveryBatchSize) {
            this.recoveryBatchSize = recoveryBatchSize;
        }

        public boolean isRecoveryHealthCheckRequired() {
            return recoveryHealthCheckRequired;
        }

        public void setRecoveryHealthCheckRequired(boolean recoveryHealthCheckRequired) {
            this.recoveryHealthCheckRequired = recoveryHealthCheckRequired;
        }

        public String getRecoveryHealthCheckEndpoint() {
            return recoveryHealthCheckEndpoint;
        }

        public void setRecoveryHealthCheckEndpoint(String recoveryHealthCheckEndpoint) {
            this.recoveryHealthCheckEndpoint = recoveryHealthCheckEndpoint;
        }

        public int getRecoveryHealthCheckTimeoutMillis() {
            return recoveryHealthCheckTimeoutMillis;
        }

        public void setRecoveryHealthCheckTimeoutMillis(int recoveryHealthCheckTimeoutMillis) {
            this.recoveryHealthCheckTimeoutMillis = recoveryHealthCheckTimeoutMillis;
        }

        public long getRecoveryConfirmationTimeoutSeconds() {
            return recoveryConfirmationTimeoutSeconds;
        }

        public void setRecoveryConfirmationTimeoutSeconds(long recoveryConfirmationTimeoutSeconds) {
            this.recoveryConfirmationTimeoutSeconds = recoveryConfirmationTimeoutSeconds;
        }

        public long getP0EscalationCount() {
            return p0EscalationCount;
        }

        public void setP0EscalationCount(long p0EscalationCount) {
            this.p0EscalationCount = p0EscalationCount;
        }

        public long getP1EscalationCount() {
            return p1EscalationCount;
        }

        public void setP1EscalationCount(long p1EscalationCount) {
            this.p1EscalationCount = p1EscalationCount;
        }
    }

    public static class Memory {
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

    public static class Skill {
        private boolean enabled = true;
        private String location = "classpath*:skills/**/*.md";
        private String projectLocation = "file:./skills/**/SKILL.md";
        private int maxActiveSkills = 3;
        private int activationThreshold = 3;
        private int promptMaxChars = 4000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }

        public String getProjectLocation() {
            return projectLocation;
        }

        public void setProjectLocation(String projectLocation) {
            this.projectLocation = projectLocation;
        }

        public int getMaxActiveSkills() {
            return maxActiveSkills;
        }

        public void setMaxActiveSkills(int maxActiveSkills) {
            this.maxActiveSkills = maxActiveSkills;
        }

        public int getActivationThreshold() {
            return activationThreshold;
        }

        public void setActivationThreshold(int activationThreshold) {
            this.activationThreshold = activationThreshold;
        }

        public int getPromptMaxChars() {
            return promptMaxChars;
        }

        public void setPromptMaxChars(int promptMaxChars) {
            this.promptMaxChars = promptMaxChars;
        }
    }

    public static class Integrations {
        private final Endpoint kubernetes = new Endpoint();
        private final Endpoint prometheus = new Endpoint();
        private final Endpoint alertmanager = new Endpoint();
        private final Endpoint incident = new Endpoint();
        private final Endpoint device = new Endpoint();
        private final Endpoint database = new Endpoint();

        public Endpoint getKubernetes() {
            return kubernetes;
        }

        public Endpoint getPrometheus() {
            return prometheus;
        }

        public Endpoint getAlertmanager() {
            return alertmanager;
        }

        public Endpoint getIncident() {
            return incident;
        }

        public Endpoint getDevice() {
            return device;
        }

        public Endpoint getDatabase() {
            return database;
        }
    }

    public static class Endpoint {
        private String endpoint;
        private int timeoutMillis = 3000;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public int getTimeoutMillis() {
            return timeoutMillis;
        }

        public void setTimeoutMillis(int timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }
    }
}
