package com.kubeoncall.common.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Safety switches and bounded collection limits for the governed AI operations workflow.
 *
 * <p>Every capability defaults to a fail-closed state. Deployments must opt in explicitly after the
 * corresponding dependency and acceptance lane are available.
 */
class AiOperationsProperties {

    private String plannerMode = "RULE_FALLBACK";
    private String plannerProvider = "openai-compatible";
    private String plannerModel = "qwen-plus";
    private String plannerSchemaVersion = "v1";
    private int plannerMaxResponseChars = 32768;
    private int plannerConnectTimeoutMillis = 3000;
    private int plannerReadTimeoutMillis = 30000;
    private boolean plannerCanaryEnabled;
    private boolean plannerCanaryFailFast = true;
    private boolean evidencePrometheusEnabled;
    private boolean evidenceLokiEnabled;
    private boolean evidenceK8sResourceStateEnabled;
    private boolean evidenceK8sEventsEnabled;
    private boolean evidencePodLogsEnabled;
    private boolean askDurableWorkflowEnabled;
    private boolean operationClosureEnabled = true;
    private boolean conclusionEvidenceUiEnabled;
    private int evidenceLookbackMinutes = 30;
    private int evidenceCollectionTimeoutMillis = 5000;
    private int evidenceMaxSnippetChars = 8192;
    private int lokiMaxWindowMinutes = 360;
    private int lokiMaxLines = 500;
    private int lokiMaxResponseBytes = 1048576;
    private List<String> allowedClusters = new ArrayList<>();
    private List<String> allowedNamespaces = new ArrayList<>();

    public String getPlannerMode() {
        return plannerMode;
    }

    public void setPlannerMode(String plannerMode) {
        this.plannerMode = plannerMode;
    }

    public String getPlannerProvider() {
        return plannerProvider;
    }

    public void setPlannerProvider(String plannerProvider) {
        this.plannerProvider = plannerProvider;
    }

    public String getPlannerModel() {
        return plannerModel;
    }

    public void setPlannerModel(String plannerModel) {
        this.plannerModel = plannerModel;
    }

    public String getPlannerSchemaVersion() {
        return plannerSchemaVersion;
    }

    public void setPlannerSchemaVersion(String plannerSchemaVersion) {
        this.plannerSchemaVersion = plannerSchemaVersion;
    }

    public int getPlannerMaxResponseChars() {
        return plannerMaxResponseChars;
    }

    public void setPlannerMaxResponseChars(int plannerMaxResponseChars) {
        this.plannerMaxResponseChars = plannerMaxResponseChars;
    }

    public int getPlannerConnectTimeoutMillis() {
        return plannerConnectTimeoutMillis;
    }

    public void setPlannerConnectTimeoutMillis(int plannerConnectTimeoutMillis) {
        this.plannerConnectTimeoutMillis = plannerConnectTimeoutMillis;
    }

    public int getPlannerReadTimeoutMillis() {
        return plannerReadTimeoutMillis;
    }

    public void setPlannerReadTimeoutMillis(int plannerReadTimeoutMillis) {
        this.plannerReadTimeoutMillis = plannerReadTimeoutMillis;
    }

    public boolean isPlannerCanaryEnabled() {
        return plannerCanaryEnabled;
    }

    public void setPlannerCanaryEnabled(boolean plannerCanaryEnabled) {
        this.plannerCanaryEnabled = plannerCanaryEnabled;
    }

    public boolean isPlannerCanaryFailFast() {
        return plannerCanaryFailFast;
    }

    public void setPlannerCanaryFailFast(boolean plannerCanaryFailFast) {
        this.plannerCanaryFailFast = plannerCanaryFailFast;
    }

    public boolean isEvidencePrometheusEnabled() {
        return evidencePrometheusEnabled;
    }

    public void setEvidencePrometheusEnabled(boolean evidencePrometheusEnabled) {
        this.evidencePrometheusEnabled = evidencePrometheusEnabled;
    }

    public boolean isEvidenceLokiEnabled() {
        return evidenceLokiEnabled;
    }

    public void setEvidenceLokiEnabled(boolean evidenceLokiEnabled) {
        this.evidenceLokiEnabled = evidenceLokiEnabled;
    }

    public boolean isEvidenceK8sResourceStateEnabled() {
        return evidenceK8sResourceStateEnabled;
    }

    public void setEvidenceK8sResourceStateEnabled(boolean evidenceK8sResourceStateEnabled) {
        this.evidenceK8sResourceStateEnabled = evidenceK8sResourceStateEnabled;
    }

    public boolean isEvidenceK8sEventsEnabled() {
        return evidenceK8sEventsEnabled;
    }

    public void setEvidenceK8sEventsEnabled(boolean evidenceK8sEventsEnabled) {
        this.evidenceK8sEventsEnabled = evidenceK8sEventsEnabled;
    }

    public boolean isEvidencePodLogsEnabled() {
        return evidencePodLogsEnabled;
    }

    public void setEvidencePodLogsEnabled(boolean evidencePodLogsEnabled) {
        this.evidencePodLogsEnabled = evidencePodLogsEnabled;
    }

    public boolean isAskDurableWorkflowEnabled() {
        return askDurableWorkflowEnabled;
    }

    public void setAskDurableWorkflowEnabled(boolean askDurableWorkflowEnabled) {
        this.askDurableWorkflowEnabled = askDurableWorkflowEnabled;
    }

    public boolean isOperationClosureEnabled() {
        return operationClosureEnabled;
    }

    public void setOperationClosureEnabled(boolean operationClosureEnabled) {
        this.operationClosureEnabled = operationClosureEnabled;
    }

    public boolean isConclusionEvidenceUiEnabled() {
        return conclusionEvidenceUiEnabled;
    }

    public void setConclusionEvidenceUiEnabled(boolean conclusionEvidenceUiEnabled) {
        this.conclusionEvidenceUiEnabled = conclusionEvidenceUiEnabled;
    }

    public int getEvidenceLookbackMinutes() {
        return evidenceLookbackMinutes;
    }

    public void setEvidenceLookbackMinutes(int evidenceLookbackMinutes) {
        this.evidenceLookbackMinutes = evidenceLookbackMinutes;
    }

    public int getEvidenceCollectionTimeoutMillis() {
        return evidenceCollectionTimeoutMillis;
    }

    public void setEvidenceCollectionTimeoutMillis(int evidenceCollectionTimeoutMillis) {
        this.evidenceCollectionTimeoutMillis = evidenceCollectionTimeoutMillis;
    }

    public int getEvidenceMaxSnippetChars() {
        return evidenceMaxSnippetChars;
    }

    public void setEvidenceMaxSnippetChars(int evidenceMaxSnippetChars) {
        this.evidenceMaxSnippetChars = evidenceMaxSnippetChars;
    }

    public int getLokiMaxWindowMinutes() {
        return lokiMaxWindowMinutes;
    }

    public void setLokiMaxWindowMinutes(int lokiMaxWindowMinutes) {
        this.lokiMaxWindowMinutes = lokiMaxWindowMinutes;
    }

    public int getLokiMaxLines() {
        return lokiMaxLines;
    }

    public void setLokiMaxLines(int lokiMaxLines) {
        this.lokiMaxLines = lokiMaxLines;
    }

    public int getLokiMaxResponseBytes() {
        return lokiMaxResponseBytes;
    }

    public void setLokiMaxResponseBytes(int lokiMaxResponseBytes) {
        this.lokiMaxResponseBytes = lokiMaxResponseBytes;
    }

    public List<String> getAllowedClusters() {
        return allowedClusters;
    }

    public void setAllowedClusters(List<String> allowedClusters) {
        this.allowedClusters = copy(allowedClusters);
    }

    public List<String> getAllowedNamespaces() {
        return allowedNamespaces;
    }

    public void setAllowedNamespaces(List<String> allowedNamespaces) {
        this.allowedNamespaces = copy(allowedNamespaces);
    }

    private static List<String> copy(List<String> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }
}
