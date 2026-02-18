package com.nitai.atlas_jobs.job.payload;

public class FailJobPayload {

    private String message;

    public FailJobPayload() {}

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}