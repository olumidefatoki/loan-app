package com.mfb.lending.module.notification.api;

import java.util.List;
import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.api.ClientContexts;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.notification.application.NotificationDirectory;
import com.mfb.lending.module.notification.application.NotificationDirectory.PreferenceChange;
import com.mfb.lending.module.notification.application.NotificationDirectory.TemplateCommand;
import com.mfb.lending.module.notification.domain.NotificationModels.NotificationMessage;
import com.mfb.lending.module.notification.domain.NotificationModels.NotificationPreference;
import com.mfb.lending.module.notification.domain.NotificationModels.NotificationTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "In-app notification history. Delivery attempts do not store phone numbers, email addresses, or verification codes.")
@SecurityRequirement(name = "bearerAuth")
class NotificationController {

    private final NotificationDirectory notifications;

    NotificationController(NotificationDirectory notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List notifications for the caller")
    public ApiResponse<PageResponse<NotificationResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<NotificationMessage> result = notifications.list(actor, status, page, size);
        return ApiResponse.success(new PageResponse<>(result.items().stream().map(NotificationResponse::from).toList(), result.page(), result.size(), result.total()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get one notification")
    public ApiResponse<NotificationResponse> get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(NotificationResponse.from(notifications.get(actor, id)));
    }

    @PostMapping("/{id}/read")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Mark an in-app notification as read")
    public ApiResponse<NotificationResponse> read(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(NotificationResponse.from(notifications.read(actor, id)));
    }
}

@RestController
@RequestMapping("/api/v1/customers/me/notification-preferences")
@Tag(name = "Notification preferences", description = "Customer channel preferences. Verification codes cannot be disabled.")
@SecurityRequirement(name = "bearerAuth")
class NotificationPreferenceController {

    private final NotificationDirectory notifications;

    NotificationPreferenceController(NotificationDirectory notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "List my notification preferences")
    public ApiResponse<List<PreferenceResponse>> list(@AuthenticationPrincipal AuthenticatedUser actor) {
        return ApiResponse.success(notifications.preferences(actor).stream().map(PreferenceResponse::from).toList());
    }

    @PutMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Replace my notification preferences")
    public ApiResponse<List<PreferenceResponse>> replace(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody PreferenceUpdateRequest request) {
        List<PreferenceChange> changes = request.preferences().stream()
                .map(item -> new PreferenceChange(item.channel(), item.eventType(), item.enabled()))
                .toList();
        return ApiResponse.success(notifications.replacePreferences(actor, changes).stream().map(PreferenceResponse::from).toList());
    }
}

@RestController
@RequestMapping("/api/v1/admin/notification-templates")
@Tag(name = "Notification templates", description = "Versioned templates for SMS, email, push, and in-app notifications.")
@SecurityRequirement(name = "bearerAuth")
class NotificationTemplateAdminController {

    private final NotificationDirectory notifications;

    NotificationTemplateAdminController(NotificationDirectory notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List notification templates")
    public ApiResponse<PageResponse<TemplateResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        PageResponse<NotificationTemplate> result = notifications.templates(actor, page, size);
        return ApiResponse.success(new PageResponse<>(result.items().stream().map(TemplateResponse::from).toList(), result.page(), result.size(), result.total()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get a notification template")
    public ApiResponse<TemplateResponse> get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(TemplateResponse.from(notifications.template(actor, id)));
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Create a notification template")
    public ApiResponse<TemplateResponse> create(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody TemplateRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(TemplateResponse.from(notifications.createTemplate(actor, request.command(), ClientContexts.from(httpRequest))));
    }

    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Revise a notification template. The previous version is kept and deactivated.")
    public ApiResponse<TemplateResponse> revise(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody TemplateRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(TemplateResponse.from(notifications.reviseTemplate(actor, id, request.command(), ClientContexts.from(httpRequest))));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Retire a notification template")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        notifications.deleteTemplate(actor, id, ClientContexts.from(httpRequest));
        return ApiResponse.success(null);
    }
}

record NotificationResponse(
        UUID id,
        UUID customerId,
        String eventType,
        String channel,
        String subject,
        String body,
        String status,
        int attemptCount,
        String lastError,
        java.time.Instant createdAt,
        java.time.Instant sentAt,
        java.time.Instant readAt) {

    static NotificationResponse from(NotificationMessage message) {
        return new NotificationResponse(
                message.id(), message.customerId(), message.eventType(), message.channel(), message.subject(), message.body(),
                message.status(), message.attemptCount(), message.lastError(), message.createdAt(), message.sentAt(), message.readAt());
    }
}

record PreferenceResponse(UUID id, String channel, String eventType, boolean enabled) {
    static PreferenceResponse from(NotificationPreference preference) {
        return new PreferenceResponse(preference.id(), preference.channel(), preference.eventType(), preference.enabled());
    }
}

record PreferenceUpdateRequest(@Valid @jakarta.validation.constraints.NotEmpty List<PreferenceItem> preferences) {
}

record PreferenceItem(
        @NotBlank @Size(max = 16) String channel,
        @NotBlank @Size(max = 64) String eventType,
        boolean enabled) {
}

record TemplateRequest(
        @NotBlank @Size(max = 64) String eventType,
        @NotBlank @Size(max = 16) String channel,
        @Size(max = 16) String locale,
        @Size(max = 255) String subject,
        @NotBlank @Size(max = 4000) String body) {

    TemplateCommand command() {
        return new TemplateCommand(eventType, channel, locale, subject, body);
    }
}

record TemplateResponse(
        UUID id,
        String code,
        String channel,
        String eventType,
        String locale,
        String subject,
        String body,
        boolean active,
        int version) {

    static TemplateResponse from(NotificationTemplate template) {
        return new TemplateResponse(
                template.id(), template.code(), template.channel(), template.eventType(), template.locale(),
                template.subject(), template.body(), template.active(), template.version());
    }
}
