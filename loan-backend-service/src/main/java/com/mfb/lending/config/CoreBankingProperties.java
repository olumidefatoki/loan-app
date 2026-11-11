package com.mfb.lending.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.core-banking")
public class CoreBankingProperties {

    private String provider = "mock";
    private String baseUrl = "";
    private String authenticationMethod = "";
    private String clientId = "";
    private String clientSecret = "";
    private String apiKey = "";
    private String apiKeyHeader = "X-Api-Key";
    private boolean allowInsecureTls;
    private Duration requestTimeout = Duration.ofSeconds(5);
    private Duration connectTimeout = Duration.ofSeconds(2);
    private long outboxPollMs = 15_000;
    private final Retry retry = new Retry();
    private final CircuitBreaker circuitBreaker = new CircuitBreaker();
    private final Webhook webhook = new Webhook();
    private Map<String, String> paths = new LinkedHashMap<>();

    public void validate() {
        String selected = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if (!selected.equals("mock") && !selected.equals("http")) {
            throw new IllegalStateException("app.core-banking.provider must be mock or http");
        }
        provider = selected;
        if (allowInsecureTls) {
            throw new IllegalStateException("Insecure TLS is not permitted for core banking");
        }
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalStateException("app.core-banking.request-timeout must be positive");
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalStateException("app.core-banking.connect-timeout must be positive");
        }
        if (outboxPollMs < 1000) {
            throw new IllegalStateException("app.core-banking.outbox-poll-ms must be at least 1000");
        }
        retry.validate();
        circuitBreaker.validate();
        webhook.validate();
        if (http()) {
            validateHttp();
        }
    }

    public boolean http() {
        return "http".equals(provider);
    }

    public String path(String operation) {
        if (paths == null || operation == null) {
            return "";
        }
        String value = paths.get(operation);
        return value == null ? "" : value.trim();
    }

    private void validateHttp() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("app.core-banking.base-url is required when the HTTP provider is selected");
        }
        String url = baseUrl.trim();
        boolean local = url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost");
        if (!url.startsWith("https://") && !local) {
            throw new IllegalStateException("app.core-banking.base-url must use HTTPS");
        }
        String method = authenticationMethod == null ? "" : authenticationMethod.trim().toUpperCase(Locale.ROOT);
        if (!method.equals("NONE") && !method.equals("API_KEY") && !method.equals("BEARER") && !method.equals("BASIC")) {
            throw new IllegalStateException(
                    "app.core-banking.authentication-method must be NONE, API_KEY, BEARER, or BASIC until a provider contract is supplied");
        }
        authenticationMethod = method;
        if (method.equals("API_KEY") && (apiKey == null || apiKey.isBlank() || apiKeyHeader == null || apiKeyHeader.isBlank())) {
            throw new IllegalStateException("API key authentication requires app.core-banking.api-key and api-key-header");
        }
        if (method.equals("BEARER") && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalStateException("Bearer authentication requires app.core-banking.api-key");
        }
        if (method.equals("BASIC") && (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank())) {
            throw new IllegalStateException("Basic authentication requires app.core-banking.client-id and client-secret");
        }
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getAuthenticationMethod() {
        return authenticationMethod;
    }

    public void setAuthenticationMethod(String authenticationMethod) {
        this.authenticationMethod = authenticationMethod;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiKeyHeader() {
        return apiKeyHeader;
    }

    public void setApiKeyHeader(String apiKeyHeader) {
        this.apiKeyHeader = apiKeyHeader;
    }

    public boolean isAllowInsecureTls() {
        return allowInsecureTls;
    }

    public void setAllowInsecureTls(boolean allowInsecureTls) {
        this.allowInsecureTls = allowInsecureTls;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public long getOutboxPollMs() {
        return outboxPollMs;
    }

    public void setOutboxPollMs(long outboxPollMs) {
        this.outboxPollMs = outboxPollMs;
    }

    public Retry getRetry() {
        return retry;
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public Webhook getWebhook() {
        return webhook;
    }

    public Map<String, String> getPaths() {
        return paths;
    }

    public void setPaths(Map<String, String> paths) {
        this.paths = paths == null ? new LinkedHashMap<>() : paths;
    }

    public static final class Retry {
        private int maxAttempts = 3;
        private Duration initialBackoff = Duration.ofMillis(200);
        private Duration maxBackoff = Duration.ofSeconds(2);
        private int multiplier = 2;

        void validate() {
            if (maxAttempts < 1) {
                throw new IllegalStateException("app.core-banking.retry.max-attempts must be at least 1");
            }
            if (initialBackoff == null || initialBackoff.isNegative()) {
                throw new IllegalStateException("app.core-banking.retry.initial-backoff must be zero or positive");
            }
            if (maxBackoff == null || maxBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalStateException("app.core-banking.retry.max-backoff must be at least the initial backoff");
            }
            if (multiplier < 1) {
                throw new IllegalStateException("app.core-banking.retry.multiplier must be at least 1");
            }
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }

        public Duration getMaxBackoff() {
            return maxBackoff;
        }

        public void setMaxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
        }

        public int getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(int multiplier) {
            this.multiplier = multiplier;
        }
    }

    public static final class CircuitBreaker {
        private int failureThreshold = 5;
        private Duration openDuration = Duration.ofSeconds(30);

        void validate() {
            if (failureThreshold < 1) {
                throw new IllegalStateException("app.core-banking.circuit-breaker.failure-threshold must be at least 1");
            }
            if (openDuration == null || openDuration.isNegative() || openDuration.isZero()) {
                throw new IllegalStateException("app.core-banking.circuit-breaker.open-duration must be positive");
            }
        }

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public Duration getOpenDuration() {
            return openDuration;
        }

        public void setOpenDuration(Duration openDuration) {
            this.openDuration = openDuration;
        }
    }

    public static final class Webhook {
        private String secret = "";
        private Duration replayWindow = Duration.ofMinutes(5);

        void validate() {
            if (replayWindow == null || replayWindow.compareTo(Duration.ofSeconds(1)) < 0) {
                throw new IllegalStateException("app.core-banking.webhook.replay-window must be at least 1 second");
            }
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret == null ? "" : secret;
        }

        public Duration getReplayWindow() {
            return replayWindow;
        }

        public void setReplayWindow(Duration replayWindow) {
            this.replayWindow = replayWindow;
        }
    }
}
