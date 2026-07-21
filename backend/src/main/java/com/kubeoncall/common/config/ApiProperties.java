package com.kubeoncall.common.config;

/** API surface limits surfaced via {@code /api/v1/capabilities} so the frontend never hardcodes them. */
public class ApiProperties {

    private int defaultPageSize = 20;
    private int maxPageSize = 100;
    private long maxUploadBytes = 52428800L;
    private int maxJsonlLines = 100000;

    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    public void setDefaultPageSize(int defaultPageSize) {
        this.defaultPageSize = defaultPageSize;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    public long getMaxUploadBytes() {
        return maxUploadBytes;
    }

    public void setMaxUploadBytes(long maxUploadBytes) {
        this.maxUploadBytes = maxUploadBytes;
    }

    public int getMaxJsonlLines() {
        return maxJsonlLines;
    }

    public void setMaxJsonlLines(int maxJsonlLines) {
        this.maxJsonlLines = maxJsonlLines;
    }
}
