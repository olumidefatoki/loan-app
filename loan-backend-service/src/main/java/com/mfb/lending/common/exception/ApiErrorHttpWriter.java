package com.mfb.lending.common.exception;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.common.api.ApiError;
import com.mfb.lending.common.correlation.CorrelationIds;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
public class ApiErrorHttpWriter {

    private final ObjectMapper objectMapper;

    public ApiErrorHttpWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, HttpStatus status, ErrorCode code, String message, String path)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String correlationId = CorrelationIds.current();
        if (correlationId != null) {
            response.setHeader(CorrelationIds.HEADER, correlationId);
        }
        objectMapper.writeValue(response.getOutputStream(), ApiError.of(code, message, path));
    }
}
