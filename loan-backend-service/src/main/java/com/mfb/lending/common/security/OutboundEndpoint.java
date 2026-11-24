package com.mfb.lending.common.security;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Rules for outbound provider URLs. A base URL is configuration, never a request field.
 * Paths stay relative so a configured path cannot retarget the host.
 */
public final class OutboundEndpoint {

    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "169.254.169.254",
            "metadata.google.internal",
            "metadata.google.com");

    private OutboundEndpoint() {
    }

    public static String requireBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Outbound base URL is required");
        }
        String trimmed = baseUrl.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Outbound base URL is invalid");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalStateException("Outbound base URL must not contain credentials");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException("Outbound base URL must not contain a query or fragment");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean local = "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
        if (!"https".equals(scheme) && !("http".equals(scheme) && local)) {
            throw new IllegalStateException("Outbound base URL must use HTTPS");
        }
        if (host.isBlank() || BLOCKED_HOSTS.contains(host) || host.startsWith("169.254.")) {
            throw new IllegalStateException("Outbound base URL host is not allowed");
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    public static String requireRelativePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("Outbound path is required");
        }
        String value = path.trim();
        if (!value.startsWith("/")
                || value.startsWith("//")
                || value.contains("\\")
                || value.contains("://")
                || value.contains("@")
                || value.contains("..")
                || value.contains("?")
                || value.contains("#")
                || value.length() > 200) {
            throw new IllegalStateException("Outbound path must be a relative path");
        }
        return value;
    }
}
