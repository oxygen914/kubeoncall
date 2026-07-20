package com.kubeoncall.common.config;

class RagProperties {

    private int defaultTopK = 5;
    private String knowledgeIndex = "kubeoncall-knowledge";
    private String knowledgeIndexAlias = "";
    private String activeDatasetVersion = "";
    private boolean vectorEnabled = false;
    private boolean crossEncoderEnabled = false;
    private String crossEncoderEndpoint;
    private String crossEncoderApiKey;
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
    private int embeddingBatchSize = 32;
    private String chunkStrategy = "recursive";
    private int chunkSize = 280;
    private int chunkOverlap = 0;
    private int rrfK = 60;
    private int rerankTopN = 30;
    private String runbookLocation = "classpath*:runbooks/*.md";
    private boolean runbookBootstrapEnabled = false;
    private boolean augmentationEnabled = false;
    private String augmentationEndpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private String augmentationApiKey = "";
    private String augmentationModel = "qwen-plus";
    private String augmentationVersion = "v1";
    private int augmentationTimeoutMillis = 10000;
    private int augmentationConcurrency = 2;
    private boolean autoBindDatasetVersion = false;
    private int jsonlMaxPayloadBytes = 5 * 1024 * 1024;
    private int jsonlMaxLines = 10_000;
    private int documentMaxContentBytes = 2 * 1024 * 1024;

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

    public String getActiveDatasetVersion() {
        return activeDatasetVersion;
    }

    public void setActiveDatasetVersion(String activeDatasetVersion) {
        this.activeDatasetVersion = activeDatasetVersion;
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

    public String getCrossEncoderApiKey() {
        return crossEncoderApiKey;
    }

    public void setCrossEncoderApiKey(String crossEncoderApiKey) {
        this.crossEncoderApiKey = crossEncoderApiKey;
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

    public int getEmbeddingBatchSize() {
        return embeddingBatchSize;
    }

    public void setEmbeddingBatchSize(int embeddingBatchSize) {
        this.embeddingBatchSize = embeddingBatchSize;
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

    public boolean isAugmentationEnabled() {
        return augmentationEnabled;
    }

    public void setAugmentationEnabled(boolean augmentationEnabled) {
        this.augmentationEnabled = augmentationEnabled;
    }

    public String getAugmentationEndpoint() {
        return augmentationEndpoint;
    }

    public void setAugmentationEndpoint(String augmentationEndpoint) {
        this.augmentationEndpoint = augmentationEndpoint;
    }

    public String getAugmentationApiKey() {
        return augmentationApiKey;
    }

    public void setAugmentationApiKey(String augmentationApiKey) {
        this.augmentationApiKey = augmentationApiKey;
    }

    public String getAugmentationModel() {
        return augmentationModel;
    }

    public void setAugmentationModel(String augmentationModel) {
        this.augmentationModel = augmentationModel;
    }

    public String getAugmentationVersion() {
        return augmentationVersion;
    }

    public void setAugmentationVersion(String augmentationVersion) {
        this.augmentationVersion = augmentationVersion;
    }

    public int getAugmentationTimeoutMillis() {
        return augmentationTimeoutMillis;
    }

    public int getAugmentationConcurrency() {
        return augmentationConcurrency;
    }

    public void setAugmentationConcurrency(int augmentationConcurrency) {
        this.augmentationConcurrency = augmentationConcurrency;
    }

    public void setAugmentationTimeoutMillis(int augmentationTimeoutMillis) {
        this.augmentationTimeoutMillis = augmentationTimeoutMillis;
    }

    public boolean isAutoBindDatasetVersion() {
        return autoBindDatasetVersion;
    }

    public void setAutoBindDatasetVersion(boolean autoBindDatasetVersion) {
        this.autoBindDatasetVersion = autoBindDatasetVersion;
    }

    public void setRunbookBootstrapEnabled(boolean runbookBootstrapEnabled) {
        this.runbookBootstrapEnabled = runbookBootstrapEnabled;
    }

    public int getJsonlMaxPayloadBytes() {
        return jsonlMaxPayloadBytes;
    }

    public void setJsonlMaxPayloadBytes(int jsonlMaxPayloadBytes) {
        this.jsonlMaxPayloadBytes = jsonlMaxPayloadBytes;
    }

    public int getJsonlMaxLines() {
        return jsonlMaxLines;
    }

    public void setJsonlMaxLines(int jsonlMaxLines) {
        this.jsonlMaxLines = jsonlMaxLines;
    }

    public int getDocumentMaxContentBytes() {
        return documentMaxContentBytes;
    }

    public void setDocumentMaxContentBytes(int documentMaxContentBytes) {
        this.documentMaxContentBytes = documentMaxContentBytes;
    }
}
