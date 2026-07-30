package com.kubeoncall.common.config;

class AgentProperties {

    private int maxLoops = 3;
    private boolean plannerLlmEnabled = true;
    private boolean postExecutionVerificationEnabled = true;
    private int postExecutionVerificationTimeoutSeconds = 120;
    private int postExecutionVerificationPollMillis = 5000;
    private int postExecutionVerificationStableWindowSeconds = 30;
    private boolean automaticRollbackEnabled = true;
    private boolean postExecutionEscalationEnabled = true;

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

    public boolean isPostExecutionVerificationEnabled() {
        return postExecutionVerificationEnabled;
    }

    public void setPostExecutionVerificationEnabled(boolean postExecutionVerificationEnabled) {
        this.postExecutionVerificationEnabled = postExecutionVerificationEnabled;
    }

    public int getPostExecutionVerificationTimeoutSeconds() {
        return postExecutionVerificationTimeoutSeconds;
    }

    public void setPostExecutionVerificationTimeoutSeconds(int postExecutionVerificationTimeoutSeconds) {
        this.postExecutionVerificationTimeoutSeconds = postExecutionVerificationTimeoutSeconds;
    }

    public int getPostExecutionVerificationPollMillis() {
        return postExecutionVerificationPollMillis;
    }

    public void setPostExecutionVerificationPollMillis(int postExecutionVerificationPollMillis) {
        this.postExecutionVerificationPollMillis = postExecutionVerificationPollMillis;
    }

    public int getPostExecutionVerificationStableWindowSeconds() {
        return postExecutionVerificationStableWindowSeconds;
    }

    public void setPostExecutionVerificationStableWindowSeconds(int postExecutionVerificationStableWindowSeconds) {
        this.postExecutionVerificationStableWindowSeconds = postExecutionVerificationStableWindowSeconds;
    }

    public boolean isAutomaticRollbackEnabled() {
        return automaticRollbackEnabled;
    }

    public void setAutomaticRollbackEnabled(boolean automaticRollbackEnabled) {
        this.automaticRollbackEnabled = automaticRollbackEnabled;
    }

    public boolean isPostExecutionEscalationEnabled() {
        return postExecutionEscalationEnabled;
    }

    public void setPostExecutionEscalationEnabled(boolean postExecutionEscalationEnabled) {
        this.postExecutionEscalationEnabled = postExecutionEscalationEnabled;
    }
}
