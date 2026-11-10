package com.mfb.lending.common.api;

import java.time.Instant;
import java.util.List;

import com.mfb.lending.common.correlation.CorrelationIds;
import com.mfb.lending.common.exception.ApiException;
import com.mfb.lending.common.exception.ErrorCode;

public record ApiError(
        boolean success,
        String code,
        String message,
        List<FieldViolation> errors,
        String correlationId,
        Instant timestamp,
        String path
) {

    public ApiError {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static ApiError of(ErrorCode code, String message, String path) {
        return of(code, message, List.of(), path);
    }

    public static ApiError of(ErrorCode code, String message, List<FieldViolation> errors, String path) {
        return new ApiError(false, code.name(), message, errors, CorrelationIds.current(), Instant.now(), path);
    }

    public static ApiError of(ApiException exception, String path) {
        return of(exception.getErrorCode(), exception.getMessage(), exception.getViolations(), path);
    }
}
