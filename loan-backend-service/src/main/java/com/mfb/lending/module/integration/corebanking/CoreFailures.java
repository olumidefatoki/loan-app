package com.mfb.lending.module.integration.corebanking;

import com.mfb.lending.common.exception.ApiException;
import com.mfb.lending.common.exception.ErrorCode;
import com.mfb.lending.common.exception.UnauthorizedException;
import org.springframework.http.HttpStatus;

final class CoreFailures {

    private CoreFailures() {
    }

    static ApiException api(CoreTransportException exception) {
        if (exception.failure() == CoreTransportException.Failure.AUTHENTICATION) {
            return new UnauthorizedException(ErrorCode.UNAUTHORIZED, "Core banking authentication failed");
        }
        return new ApiException(ErrorCode.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "Core banking is unavailable");
    }
}
