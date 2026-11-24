package com.mfb.lending.common.security;

import java.io.IOException;

import com.mfb.lending.common.exception.ApiErrorHttpWriter;
import com.mfb.lending.common.exception.ApiException;
import com.mfb.lending.config.SecurityProperties;
import com.mfb.lending.module.auth.application.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private final SecurityProperties properties;
    private final RateLimiter rateLimiter;
    private final ApiErrorHttpWriter writer;

    public ApiRateLimitFilter(SecurityProperties properties, RateLimiter rateLimiter, ApiErrorHttpWriter writer) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        SecurityProperties.ApiRateLimit limit = properties.getApiRateLimit();
        if (!limit.isEnabled() || probe(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            rateLimiter.assertKeyed("api", clientKey(request), limit.getMaxRequests(), limit.getWindow());
            filterChain.doFilter(request, response);
        } catch (ApiException ex) {
            writer.write(response, ex.getStatus(), ex.getErrorCode(), ex.getMessage(), request.getRequestURI());
        }
    }

    private static boolean probe(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) {
            return false;
        }
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI();
        }
        return "/api/v1/system/version".equals(path) || path.startsWith("/actuator/health");
    }

    private static String clientKey(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return "token:" + authorization.substring(7).trim();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
