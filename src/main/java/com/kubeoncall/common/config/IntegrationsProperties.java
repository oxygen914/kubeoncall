package com.kubeoncall.common.config;

class IntegrationsProperties {

    private final KubeOnCallProperties.Endpoint kubernetes = new KubeOnCallProperties.Endpoint();
    private final KubeOnCallProperties.Endpoint prometheus = new KubeOnCallProperties.Endpoint();
    private final KubeOnCallProperties.Endpoint alertmanager = new KubeOnCallProperties.Endpoint();
    private final KubeOnCallProperties.Endpoint notification = new KubeOnCallProperties.Endpoint();
    private final KubeOnCallProperties.Endpoint incident = new KubeOnCallProperties.Endpoint();
    private final KubeOnCallProperties.Endpoint device = new KubeOnCallProperties.Endpoint();
    private final KubeOnCallProperties.Endpoint database = new KubeOnCallProperties.Endpoint();

    public KubeOnCallProperties.Endpoint getKubernetes() {
        return kubernetes;
    }

    public KubeOnCallProperties.Endpoint getPrometheus() {
        return prometheus;
    }

    public KubeOnCallProperties.Endpoint getAlertmanager() {
        return alertmanager;
    }

    public KubeOnCallProperties.Endpoint getNotification() {
        return notification;
    }

    public KubeOnCallProperties.Endpoint getIncident() {
        return incident;
    }

    public KubeOnCallProperties.Endpoint getDevice() {
        return device;
    }

    public KubeOnCallProperties.Endpoint getDatabase() {
        return database;
    }
}
