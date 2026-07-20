package com.kubeoncall.common.config;

class ChangeEventProperties {

    private boolean webhookEnabled = false;
    private String webhookAuthMode = "bearer";
    private String webhookToken = "";
    private int webhookMaxPayloadBytes = 1048576;
    private String githubWebhookSecret = "";
    private String gitlabWebhookToken = "";
    private String jenkinsWebhookToken = "";
    private String argocdWebhookToken = "";

    public boolean isWebhookEnabled() {
        return webhookEnabled;
    }

    public void setWebhookEnabled(boolean webhookEnabled) {
        this.webhookEnabled = webhookEnabled;
    }

    public String getWebhookAuthMode() {
        return webhookAuthMode;
    }

    public void setWebhookAuthMode(String webhookAuthMode) {
        this.webhookAuthMode = webhookAuthMode;
    }

    public String getWebhookToken() {
        return webhookToken;
    }

    public void setWebhookToken(String webhookToken) {
        this.webhookToken = webhookToken;
    }

    public int getWebhookMaxPayloadBytes() {
        return webhookMaxPayloadBytes;
    }

    public void setWebhookMaxPayloadBytes(int webhookMaxPayloadBytes) {
        this.webhookMaxPayloadBytes = webhookMaxPayloadBytes;
    }

    public String getGithubWebhookSecret() {
        return githubWebhookSecret;
    }

    public void setGithubWebhookSecret(String githubWebhookSecret) {
        this.githubWebhookSecret = githubWebhookSecret;
    }

    public String getGitlabWebhookToken() {
        return gitlabWebhookToken;
    }

    public void setGitlabWebhookToken(String gitlabWebhookToken) {
        this.gitlabWebhookToken = gitlabWebhookToken;
    }

    public String getJenkinsWebhookToken() {
        return jenkinsWebhookToken;
    }

    public void setJenkinsWebhookToken(String jenkinsWebhookToken) {
        this.jenkinsWebhookToken = jenkinsWebhookToken;
    }

    public String getArgocdWebhookToken() {
        return argocdWebhookToken;
    }

    public void setArgocdWebhookToken(String argocdWebhookToken) {
        this.argocdWebhookToken = argocdWebhookToken;
    }
}
