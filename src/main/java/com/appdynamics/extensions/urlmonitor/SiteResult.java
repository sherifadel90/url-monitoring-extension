/*
 * Copyright 2014. AppDynamics LLC and its affiliates.
 * All Rights Reserved.
 * This is unpublished proprietary source code of AppDynamics LLC and its affiliates.
 * The copyright notice above does not evidence any actual or intended publication of such source code.
 */

package com.appdynamics.extensions.urlmonitor;

import java.util.HashMap;

@SuppressWarnings("unused")
public class SiteResult {
    public enum ResultStatus {
        UNKNOWN,
        CANCELED,
        FAILED,
        ERROR,
        SUCCESS
    }

    private long firstByteTime;
    private long downloadTime;
    private long totalTime;
    private ResultStatus status;
    private int responseCode;
    private long responseBytes;
    private long successPercentage;
    private HashMap<String, Integer> matches = new HashMap<String, Integer>();

    public SiteResult() {
    }

    public SiteResult(ResultStatus status) {
        this.status = status;
    }

    public long getFirstByteTime() {
        return firstByteTime;
    }

    public void setFirstByteTime(long firstByteTime) {
        this.firstByteTime = firstByteTime;
    }

    public ResultStatus getStatus() {
        return status;
    }

    public void setStatus(ResultStatus status) {
        this.status = status;
    }

    public int getResponseCode() {
        return responseCode;
    }

    public void setResponseCode(int responseCode) {
        this.responseCode = responseCode;
    }

    public long getResponseBytes() {
        return responseBytes;
    }

    public void setResponseBytes(long responseBytes) {
        this.responseBytes = responseBytes;
    }
    
     public long getSuccessPercentage() {
        return successPercentage;
    }

    public void setSuccessPercentage(long successPercentage) {
        this.successPercentage = successPercentage;
    }

    public HashMap<String, Integer> getMatches() { return matches; }

    public void setMatches(HashMap<String, Integer> matches) { this.matches = matches; }

    public long getDownloadTime() { return downloadTime; }

    public void setDownloadTime(long downloadTime) { this.downloadTime = downloadTime; }

    public long getTotalTime() { return totalTime; }

    public void setTotalTime(long totalTime) { this.totalTime = totalTime; }
}
