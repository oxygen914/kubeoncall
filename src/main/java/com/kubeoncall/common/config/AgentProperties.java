package com.kubeoncall.common.config;

class AgentProperties {

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
