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
        private boolean vectorEnabled = false;
        private boolean crossEncoderEnabled = false;
        private String crossEncoderEndpoint;
        private int crossEncoderTimeoutMillis = 3000;
        private int lexicalCandidateTopN = 20;
        private String vectorEndpoint;
        private int vectorTimeoutMillis = 3000;
        private int vectorCandidateTopN = 20;
        private int rrfK = 60;
        private int rerankTopN = 20;

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

    public static class Integrations {
        private final Endpoint kubernetes = new Endpoint();
        private final Endpoint prometheus = new Endpoint();
        private final Endpoint alertmanager = new Endpoint();
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
