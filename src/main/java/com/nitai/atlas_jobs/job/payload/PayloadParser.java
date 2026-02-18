package com.nitai.atlas_jobs.job.payload;

import com.nitai.atlas_jobs.job.InvalidJobPayloadException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class PayloadParser {

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public PayloadParser(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public SleepJobPayload parseSleepPayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return new SleepJobPayload();
        }
        SleepJobPayload payload = readValue(payloadJson, SleepJobPayload.class, "SLEEP_JOB");
        validate(payload, "SLEEP_JOB");
        return payload;
    }

    public FailJobPayload parseFailPayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return new FailJobPayload();
        }
        FailJobPayload payload = readValue(payloadJson, FailJobPayload.class, "FAIL_JOB");
        validate(payload, "FAIL_JOB");
        return payload;
    }

    private <T> T readValue(String payloadJson, Class<T> type, String jobType) {
        try {
            return objectMapper.readValue(payloadJson, type);
        } catch (Exception e) {
            throw new InvalidJobPayloadException(
                    "Invalid JSON payload for " + jobType + ": " + e.getMessage(), e);
        }
    }

    private <T> void validate(T payload, String jobType) {
        Set<ConstraintViolation<T>> violations = validator.validate(payload);
        if (violations.isEmpty()) return;

        String details = violations.stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining(", "));
        throw new InvalidJobPayloadException("Invalid payload for " + jobType + ": " + details);
    }
}