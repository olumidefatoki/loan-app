package com.mfb.lending.config;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionGuardTest {

    @Test
    void productionRejectsTheMockProvider() {
        ProductionGuard guard = new ProductionGuard(new CoreBankingProperties(), new SecurityProperties(), new AuthProperties());
        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider=http");
    }

    @Test
    void productionRejectsDevelopmentSecrets() {
        CoreBankingProperties core = httpCore("production-webhook-secret");
        SecurityProperties security = security("Str0ng-Passw0rd!");
        ProductionGuard guard = new ProductionGuard(core, security, authSecrets());
        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("development secret");
    }

    @Test
    void productionAcceptsAConfiguredHttpProvider() {
        CoreBankingProperties core = httpCore("production-webhook-secret");
        SecurityProperties security = security("Production-Ops-Passw0rd");
        ProductionGuard guard = new ProductionGuard(core, security, authSecrets());
        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
    }

    private static CoreBankingProperties httpCore(String webhookSecret) {
        CoreBankingProperties core = new CoreBankingProperties();
        core.setProvider("http");
        core.setBaseUrl("https://core.bank.example");
        core.setAuthenticationMethod("BEARER");
        core.setApiKey("production-core-api-key");
        core.getWebhook().setSecret(webhookSecret);
        return core;
    }

    private static SecurityProperties security(String opsPassword) {
        SecurityProperties security = new SecurityProperties();
        security.setOpsPassword(opsPassword);
        return security;
    }

    private static AuthProperties authSecrets() {
        String key = Base64.getEncoder().encodeToString(new byte[] {
                7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
                7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7
        });
        AuthProperties auth = new AuthProperties();
        auth.getJwt().setHmacSecret(key);
        auth.setEncryptionKey(key);
        auth.getOtp().setPepper("production-otp-pepper-value");
        auth.afterPropertiesSet();
        return auth;
    }
}
