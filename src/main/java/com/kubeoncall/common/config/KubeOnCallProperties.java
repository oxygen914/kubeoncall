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

    public static class Agent {
        private int maxLoops = 3;

        public int getMaxLoops() {
            return maxLoops;
        }

        public void setMaxLoops(int maxLoops) {
            this.maxLoops = maxLoops;
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
}
