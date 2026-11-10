package com.mfb.lending.common.exception;

import java.util.List;

import com.mfb.lending.common.api.FieldViolation;
import org.springframework.http.HttpStatus;

public class ValidationException extends ApiException {

    public ValidationException(List<FieldViolation> violations) {
        super(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "Request validation failed", violations);
    }

    public static ValidationException field(String field, String message) {
        return new ValidationException(List.of(new FieldViolation(field, message)));
    }
}
