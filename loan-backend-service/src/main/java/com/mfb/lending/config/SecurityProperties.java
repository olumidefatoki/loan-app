package com.mfb.lending.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    private String opsUsername = "ops";
    private String opsPassword = "";
    private List<String> allowedOrigins = new ArrayList<>();

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

    public List<String> resolvedAllowedOrigins() {
        if (allowedOrigins == null) {
            return List.of();
        }
        return allowedOrigins.stream()
                .filter(origin -> origin != null && !origin.isBlank())
                .toList();
    }
}
