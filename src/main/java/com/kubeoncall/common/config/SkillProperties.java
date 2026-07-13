package com.kubeoncall.common.config;

class SkillProperties {

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
