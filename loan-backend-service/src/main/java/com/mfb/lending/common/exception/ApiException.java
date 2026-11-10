package com.mfb.lending.common.exception;

import java.util.List;

import com.mfb.lending.common.api.FieldViolation;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final HttpStatus status;
    private final List<FieldViolation> violations;

    public ApiException(ErrorCode errorCode, HttpStatus status, String message) {
        this(errorCode, status, message, List.of());
    }

    public ApiException(ErrorCode errorCode, HttpStatus status, String message, List<FieldViolation> violations) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
        this.violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public List<FieldViolation> getViolations() {
        return violations;
    }
}
