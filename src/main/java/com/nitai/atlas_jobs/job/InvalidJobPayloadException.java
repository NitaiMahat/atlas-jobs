package com.nitai.atlas_jobs.job;

public class InvalidJobPayloadException extends RuntimeException {
    public InvalidJobPayloadException(String message) { super(message); }
    public InvalidJobPayloadException(String message, Throwable cause) { super(message, cause); }
}