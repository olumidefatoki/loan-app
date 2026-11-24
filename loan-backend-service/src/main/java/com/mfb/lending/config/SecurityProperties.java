package com.mfb.lending.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    private String opsUsername = "ops";
    private String opsPassword = "";
    private List<String> allowedOrigins = new ArrayList<>();
    private final ApiRateLimit apiRateLimit = new ApiRateLimit();

    public String getOpsUsername() {
        return opsUsername;
    }

    public void setOpsUsername(String opsUsername) {
        this.opsUsername = opsUsername;
    }

    public String getOpsPassword() {
        return opsPassword;
    }

    public void setOpsPassword(String opsPassword) {
        this.opsPassword = opsPassword;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public ApiRateLimit getApiRateLimit() {
        return apiRateLimit;
    }

    public List<String> resolvedAllowedOrigins() {
        if (allowedOrigins == null) {
            return List.of();
        }
        return allowedOrigins.stream()
                .filter(origin -> origin != null && !origin.isBlank())
                .toList();
    }

    public static class ApiRateLimit {
        private boolean enabled = true;
        private int maxRequests = 600;
        private Duration window = Duration.ofMinutes(1);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxRequests() {
            return maxRequests;
        }

        public void setMaxRequests(int maxRequests) {
            this.maxRequests = maxRequests;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }
    }
}
