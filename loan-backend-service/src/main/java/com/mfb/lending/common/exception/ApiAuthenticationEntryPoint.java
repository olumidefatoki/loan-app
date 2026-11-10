package com.mfb.lending.common.exception;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiErrorHttpWriter writer;

    public ApiAuthenticationEntryPoint(ApiErrorHttpWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        String path = request.getRequestURI() == null ? "" : request.getRequestURI();
        String challenge = path.startsWith("/api/") ? "Bearer" : "Basic realm=\"lending\"";
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
        writer.write(response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Authentication is required", request.getRequestURI());
    }
}
