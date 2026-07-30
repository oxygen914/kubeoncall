package com.kubeoncall.common.config;

class SkillProperties {

    private boolean enabled = true;
    private String location = "classpath*:skills/**/*.md";
    private String projectLocation = "file:./skills/**/SKILL.md";
    private int maxActiveSkills = 3;
    private int activationThreshold = 3;
    private int promptMaxChars = 4000;
    private int promptTokenBudget = 900;
    private String versionConflictPolicy = "PREFER_PROJECT";
    private boolean automaticAlertDiagnosisEnabled = true;

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

    public int getPromptTokenBudget() {
        return promptTokenBudget;
    }

    public void setPromptTokenBudget(int promptTokenBudget) {
        this.promptTokenBudget = promptTokenBudget;
    }

    public String getVersionConflictPolicy() {
        return versionConflictPolicy;
    }

    public void setVersionConflictPolicy(String versionConflictPolicy) {
        this.versionConflictPolicy = versionConflictPolicy;
    }

    public boolean isAutomaticAlertDiagnosisEnabled() {
        return automaticAlertDiagnosisEnabled;
    }

    public void setAutomaticAlertDiagnosisEnabled(boolean automaticAlertDiagnosisEnabled) {
        this.automaticAlertDiagnosisEnabled = automaticAlertDiagnosisEnabled;
    }
}
