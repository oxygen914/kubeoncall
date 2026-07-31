package com.kubeoncall.common.config;

class StorageProperties {

    private final KubeOnCallProperties.Storage.Minio minio = new KubeOnCallProperties.Storage.Minio();

    public KubeOnCallProperties.Storage.Minio getMinio() {
        return minio;
    }
}
