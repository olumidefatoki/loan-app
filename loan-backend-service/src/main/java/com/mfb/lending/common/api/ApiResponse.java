package com.mfb.lending.common.api;

import java.time.Instant;

import com.mfb.lending.common.correlation.CorrelationIds;
import com.mfb.lending.common.exception.ErrorCode;

public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        String correlationId,
        Instant timestamp
) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, ErrorCode.OK.name(), "Success", data, CorrelationIds.current(), Instant.now());
    }
}
