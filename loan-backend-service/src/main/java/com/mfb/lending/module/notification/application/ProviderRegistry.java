package com.mfb.lending.module.notification.application;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mfb.lending.config.PlatformOperationsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ProviderRegistry implements NotificationProviders {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    private final Map<String, NotificationProvider> providers;
    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformOperationsProperties properties;

    public ProviderRegistry(
            List<NotificationProvider> providers,
            NamedParameterJdbcTemplate jdbc,
            PlatformOperationsProperties properties) {
        this.providers = providers.stream().collect(java.util.stream.Collectors.toMap(NotificationProvider::code, provider -> provider));
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public NotificationProvider provider(String channel) {
        String configured = configured(channel);
        NotificationProvider provider = providers.get(configured);
        if (provider == null || !provider.channel().equals(channel)) {
            throw new IllegalStateException("No notification provider is configured for " + channel);
        }
        return provider;
    }

    private String configured(String channel) {
        String key = switch (channel) {
            case "SMS" -> "notifications.provider.sms";
            case "EMAIL" -> "notifications.provider.email";
            case "PUSH" -> "notifications.provider.push";
            default -> "notifications.provider.in-app";
        };
        try {
            String value = jdbc.query(
                    "SELECT config_value FROM system_configurations WHERE config_key = :key",
                    new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("key", key),
                    (rs, row) -> rs.getString("config_value")).stream().findFirst().orElse(null);
            if (value != null && !value.isBlank()) {
                return value.trim().toLowerCase(Locale.ROOT);
            }
        } catch (RuntimeException ex) {
            log.warn("Notification provider configuration could not be read for {}", channel);
        }
        return properties.providerCode(channel);
    }
}

@Component
class LoggingNotificationProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationProvider.class);

    private final String channel;
    private final String code;

    LoggingNotificationProvider(String channel, String code) {
        this.channel = channel;
        this.code = code;
    }

    @Override
    public String channel() {
        return channel;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public DeliveryResult send(OutboundNotice notice) {
        if (notice.destination() == null || notice.destination().isBlank()) {
            return DeliveryResult.skipped("PUSH".equals(channel) ? "No device token" : "No destination");
        }
        log.info("Accepted {} notification {}", channel, notice.notificationId());
        return DeliveryResult.accepted(code + "-" + notice.notificationId());
    }
}

@org.springframework.context.annotation.Configuration
class NotificationProviderConfiguration {

    @org.springframework.context.annotation.Bean
    NotificationProvider smsProvider() {
        return new LoggingNotificationProvider("SMS", "mock-sms");
    }

    @org.springframework.context.annotation.Bean
    NotificationProvider emailProvider() {
        return new LoggingNotificationProvider("EMAIL", "mock-email");
    }

    @org.springframework.context.annotation.Bean
    NotificationProvider pushProvider() {
        return new LoggingNotificationProvider("PUSH", "mock-push");
    }

    @org.springframework.context.annotation.Bean
    NotificationProvider inAppProvider() {
        return new LoggingNotificationProvider("IN_APP", "in-app");
    }
}
