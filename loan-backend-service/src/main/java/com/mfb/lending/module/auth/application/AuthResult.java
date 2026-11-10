package com.mfb.lending.module.auth.application;

import com.mfb.lending.common.exception.ApiException;
import com.mfb.lending.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public record AuthResult<T>(T value, HttpStatus status, ErrorCode code, String message) {

    public static <T> AuthResult<T> ok(T value) {
        return new AuthResult<>(value, null, null, null);
    }

    public static <T> AuthResult<T> fail(HttpStatus status, ErrorCode code, String message) {
        return new AuthResult<>(null, status, code, message);
    }

    public T required() {
        if (code != null) {
            throw new ApiException(code, status, message);
        }
        return value;
    }
}
