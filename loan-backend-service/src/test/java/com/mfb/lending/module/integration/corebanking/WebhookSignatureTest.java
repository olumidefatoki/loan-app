package com.mfb.lending.module.integration.corebanking;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {

    @Test
    void signatureMatchesTheTimestampAndBody() {
        byte[] body = "{\"eventType\":\"REPAYMENT_POSTED\"}".getBytes(StandardCharsets.UTF_8);
        String signature = WebhookSignature.sign("secret", "1000", body);
        assertThat(WebhookSignature.matches("secret", "1000", body, signature)).isTrue();
        assertThat(WebhookSignature.matches("secret", "1000", body, signature.toUpperCase())).isTrue();
        assertThat(WebhookSignature.matches("other", "1000", body, signature)).isFalse();
        assertThat(WebhookSignature.matches("secret", "1001", body, signature)).isFalse();
        assertThat(WebhookSignature.matches("secret", "1000", " ".getBytes(StandardCharsets.UTF_8), signature)).isFalse();
    }
}
