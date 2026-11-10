package com.mfb.lending.module.auth.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;

import com.mfb.lending.common.exception.ApiErrorHttpWriter;
import com.mfb.lending.common.exception.ErrorCode;
import com.mfb.lending.common.exception.UnauthorizedException;
import com.mfb.lending.module.auth.domain.SessionRecord;
import com.mfb.lending.module.auth.domain.UserRecord;
import com.mfb.lending.module.auth.domain.UserStatus;
import com.mfb.lending.module.auth.persistence.SecurityProfileRepository;
import com.mfb.lending.module.auth.persistence.SessionRepository;
import com.mfb.lending.module.auth.persistence.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final SessionRepository sessions;
    private final UserRepository users;
    private final SecurityProfileRepository profiles;
    private final ApiErrorHttpWriter writer;
    private final Clock clock;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            SessionRepository sessions,
            UserRepository users,
            SecurityProfileRepository profiles,
            ApiErrorHttpWriter writer,
            Clock clock) {
        this.jwtService = jwtService;
        this.sessions = sessions;
        this.users = users;
        this.profiles = profiles;
        this.writer = writer;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            authenticate(header.substring(7).trim());
            filterChain.doFilter(request, response);
        } catch (UnauthorizedException ex) {
            SecurityContextHolder.clearContext();
            if (allowsAnonymous(request)) {
                filterChain.doFilter(request, response);
                return;
            }
            writer.write(response, HttpStatus.UNAUTHORIZED, ex.getErrorCode(), ex.getMessage(), request.getRequestURI());
        }
    }

    private void authenticate(String token) {
        JwtService.AccessClaims claims = jwtService.parseAccessToken(token);
        Instant now = clock.instant();
        SessionRecord session = sessions.findById(claims.sessionId())
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.TOKEN_REVOKED, "The session has been revoked"));
        if (!session.userId().equals(claims.userId())) {
            throw new UnauthorizedException(ErrorCode.UNAUTHORIZED, "The access token is not valid");
        }
        if ("REVOKED".equals(session.status()) || session.revokedAt() != null) {
            throw new UnauthorizedException(ErrorCode.TOKEN_REVOKED, "The session has been revoked");
        }
        if (!session.expiresAt().isAfter(now)) {
            throw new UnauthorizedException(ErrorCode.TOKEN_EXPIRED, "The session has expired");
        }
        UserRecord user = users.findById(claims.userId())
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.UNAUTHORIZED, "The access token is not valid"));
        if (user.status() != UserStatus.ACTIVE) {
            throw new UnauthorizedException(ErrorCode.UNAUTHORIZED, "The account is not active");
        }
        AuthenticatedUser principal = AuthenticatedUser.from(user, session.id(), profiles.load(user.id()));
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal,
                "N/A",
                principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        sessions.touch(session.id(), now);
    }

    private static boolean allowsAnonymous(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI();
        }
        if ("GET".equals(request.getMethod())
                && ("/api/v1/system/version".equals(path) || path.startsWith("/actuator/health"))) {
            return true;
        }
        if (!"POST".equals(request.getMethod())) {
            return false;
        }
        return switch (path) {
            case "/api/v1/auth/register",
                    "/api/v1/auth/verify-otp",
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh-token",
                    "/api/v1/auth/forgot-password",
                    "/api/v1/auth/reset-password",
                    "/api/v1/admin/auth/login",
                    "/api/v1/admin/auth/mfa/verify",
                    "/api/v1/admin/auth/refresh-token" -> true;
            default -> false;
        };
    }
}
