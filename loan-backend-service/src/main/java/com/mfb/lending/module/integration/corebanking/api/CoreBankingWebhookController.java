package com.mfb.lending.module.integration.corebanking.api;

import java.io.IOException;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.integration.corebanking.WebhookIngestionService;
import com.mfb.lending.module.integration.corebanking.WebhookSignature;
import com.mfb.lending.module.integration.corebanking.dto.WebhookAcceptedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks/core-banking")
@Tag(name = "Core banking webhooks", description = """
        Accepts a platform webhook contract. The body is signed with HMAC-SHA256 over timestamp + '.' + raw body. \
        Send X-Core-Banking-Message-Id, X-Core-Banking-Timestamp as epoch seconds, and X-Core-Banking-Signature as hex. \
        Replays outside the configured window are rejected. A repeated message id is acknowledged and not applied again. \
        This contract is not a core banking vendor schema.""")
public class CoreBankingWebhookController {

    private final WebhookIngestionService webhooks;

    public CoreBankingWebhookController(WebhookIngestionService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping(value = "/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Accept a core banking event", description = "Verifies the signature and replay window, deduplicates the message, and applies a confirmed repayment or reversal only after an adapter enquiry agrees.")
    public ApiResponse<WebhookAcceptedResponse> events(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        var result = webhooks.accept(
                body,
                request.getHeader(WebhookSignature.MESSAGE_ID),
                request.getHeader(WebhookSignature.TIMESTAMP),
                request.getHeader(WebhookSignature.SIGNATURE));
        String state = result.transaction() == null ? null : result.transaction().state().name();
        var id = result.transaction() == null ? null : result.transaction().id();
        return ApiResponse.success(new WebhookAcceptedResponse(result.duplicate(), id, state));
    }
}
