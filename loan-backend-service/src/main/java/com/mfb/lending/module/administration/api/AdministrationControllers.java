package com.mfb.lending.module.administration.api;

import java.time.LocalDate;
import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.administration.application.AdministrationService;
import com.mfb.lending.module.administration.application.AdministrationService.CalendarDetail;
import com.mfb.lending.module.administration.application.AdministrationService.ConfigCommand;
import com.mfb.lending.module.administration.application.AdministrationService.ConfigWrite;
import com.mfb.lending.module.administration.domain.AdministrationModels.Branch;
import com.mfb.lending.module.administration.domain.AdministrationModels.BusinessCalendar;
import com.mfb.lending.module.administration.domain.AdministrationModels.ChangeRequest;
import com.mfb.lending.module.administration.domain.AdministrationModels.FeatureFlag;
import com.mfb.lending.module.administration.domain.AdministrationModels.OperationalHealth;
import com.mfb.lending.module.administration.domain.AdministrationModels.PublicHoliday;
import com.mfb.lending.module.administration.domain.AdministrationModels.SystemConfiguration;
import com.mfb.lending.module.auth.api.ClientContexts;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/admin/branches")
@Tag(name = "Branches", description = "Branch administration.")
@SecurityRequirement(name = "bearerAuth")
class BranchAdminController {

    private final AdministrationService admin;

    BranchAdminController(AdministrationService admin) {
        this.admin = admin;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List branches")
    public ApiResponse<PageResponse<Branch>> list(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(admin.branches(actor, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get a branch")
    public ApiResponse<Branch> get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(admin.branch(actor, id));
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Create a branch")
    public ApiResponse<Branch> create(
            @AuthenticationPrincipal AuthenticatedUser actor, @Valid @RequestBody BranchRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(admin.createBranch(actor, request.branch(), ClientContexts.from(httpRequest)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Update a branch")
    public ApiResponse<Branch> update(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id, @Valid @RequestBody BranchRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(admin.updateBranch(actor, id, request.branch(), ClientContexts.from(httpRequest)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Delete a branch that is not referenced")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id, HttpServletRequest httpRequest) {
        admin.deleteBranch(actor, id, ClientContexts.from(httpRequest));
        return ApiResponse.success(null);
    }
}

@RestController
@RequestMapping("/api/v1/admin/system-configurations")
@Tag(name = "System configuration", description = "Platform configuration. Sensitive and integration changes stay pending until a different administrator approves them. Secret values stay in the environment.")
@SecurityRequirement(name = "bearerAuth")
class SystemConfigurationAdminController {

    private final AdministrationService admin;

    SystemConfigurationAdminController(AdministrationService admin) {
        this.admin = admin;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List system configuration. Sensitive values are masked.")
    public ApiResponse<PageResponse<SystemConfiguration>> list(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(admin.configurations(actor, page, size));
    }

    @GetMapping("/changes")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List configuration change requests")
    public ApiResponse<PageResponse<ChangeRequest>> changes(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(admin.changes(actor, status, page, size));
    }

    @PostMapping("/changes/{id}/approve")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Approve a configuration change. The requester cannot approve it.")
    public ApiResponse<ChangeRequest> approve(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id, @Valid @RequestBody ReasonRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(admin.approve(actor, id, request.reason(), ClientContexts.from(httpRequest)));
    }

    @PostMapping("/changes/{id}/reject")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Reject a configuration change")
    public ApiResponse<ChangeRequest> reject(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id, @Valid @RequestBody ReasonRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(admin.reject(actor, id, request.reason(), ClientContexts.from(httpRequest)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get one system configuration. Sensitive values are masked.")
    public ApiResponse<SystemConfiguration> get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(admin.configuration(actor, id));
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Create or replace a system configuration. Sensitive changes return pending approval.")
    public ResponseEntity<ApiResponse<ConfigWrite>> create(
            @AuthenticationPrincipal AuthenticatedUser actor, @Valid @RequestBody ConfigRequest request, HttpServletRequest httpRequest) {
        ConfigWrite result = admin.saveConfiguration(actor, request.command("UPSERT"), ClientContexts.from(httpRequest));
        return ResponseEntity.status(result.pending() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(ApiResponse.success(result));
    }

    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Update a system configuration. The path id selects the current row; the key in the body is the setting being changed.")
    public ResponseEntity<ApiResponse<ConfigWrite>> update(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id, @Valid @RequestBody ConfigRequest request, HttpServletRequest httpRequest) {
        admin.configuration(actor, id);
        ConfigWrite result = admin.saveConfiguration(actor, request.command("UPSERT"), ClientContexts.from(httpRequest));
        return ResponseEntity.status(result.pending() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(ApiResponse.success(result));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Delete a system configuration. Sensitive deletes wait for approval.")
    public ResponseEntity<ApiResponse<ConfigWrite>> delete(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id, HttpServletRequest httpRequest) {
        ConfigWrite result = admin.deleteConfigurationById(actor, id, ClientContexts.from(httpRequest));
        return ResponseEntity.status(result.pending() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(ApiResponse.success(result));
    }
}

@RestController
@RequestMapping("/api/v1/admin/business-calendars")
@Tag(name = "Business calendars", description = "Calendars, weekends, and public holidays.")
@SecurityRequirement(name = "bearerAuth")
class BusinessCalendarAdminController {

    private final AdministrationService admin;

    BusinessCalendarAdminController(AdministrationService admin) {
        this.admin = admin;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List business calendars")
    public ApiResponse<java.util.List<BusinessCalendar>> list(@AuthenticationPrincipal AuthenticatedUser actor) {
        return ApiResponse.success(admin.calendars(actor));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get a business calendar and its holidays")
    public ApiResponse<CalendarDetail> get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(admin.calendar(actor, id));
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Create a business calendar")
    public ApiResponse<BusinessCalendar> create(
            @AuthenticationPrincipal AuthenticatedUser actor, @Valid @RequestBody CalendarRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(admin.createCalendar(actor, request.calendar(), ClientContexts.from(httpRequest)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Update a business calendar")
    public ApiResponse<BusinessCalendar> update(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id, @Valid @RequestBody CalendarRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(admin.updateCalendar(actor, id, request.calendar(), ClientContexts.from(httpRequest)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Delete a business calendar and its holidays")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id, HttpServletRequest httpRequest) {
        admin.deleteCalendar(actor, id, ClientContexts.from(httpRequest));
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/holidays")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Add a public holiday")
    public ApiResponse<PublicHoliday> addHoliday(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id, @Valid @RequestBody HolidayRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(admin.addHoliday(actor, id, request.holidayDate(), request.name(), ClientContexts.from(httpRequest)));
    }

    @DeleteMapping("/{id}/holidays/{holidayId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Remove a public holiday")
    public ApiResponse<Void> deleteHoliday(
            @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id, @PathVariable UUID holidayId, HttpServletRequest httpRequest) {
        admin.deleteHoliday(actor, id, holidayId, ClientContexts.from(httpRequest));
        return ApiResponse.success(null);
    }
}

@RestController
@RequestMapping("/api/v1/admin/feature-flags")
@Tag(name = "Feature flags", description = "Operational feature flags. Sensitive flag changes require a second administrator.")
@SecurityRequirement(name = "bearerAuth")
class FeatureFlagAdminController {

    private final AdministrationService admin;

    FeatureFlagAdminController(AdministrationService admin) {
        this.admin = admin;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List feature flags")
    public ApiResponse<java.util.List<FeatureFlag>> list(@AuthenticationPrincipal AuthenticatedUser actor) {
        return ApiResponse.success(admin.flags(actor));
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Create or update a feature flag")
    public ResponseEntity<ApiResponse<ConfigWrite>> save(
            @AuthenticationPrincipal AuthenticatedUser actor, @Valid @RequestBody FlagRequest request, HttpServletRequest httpRequest) {
        ConfigWrite result = admin.saveFlag(actor, request.flagKey(), request.enabled(), request.description(), request.sensitive(), ClientContexts.from(httpRequest));
        return ResponseEntity.status(result.pending() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(ApiResponse.success(result));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Delete a feature flag that is not sensitive")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id, HttpServletRequest httpRequest) {
        admin.deleteFlag(actor, id, ClientContexts.from(httpRequest));
        return ApiResponse.success(null);
    }
}

@RestController
@RequestMapping("/api/v1/admin/operations")
@Tag(name = "Operations", description = "Operational health for notifications, compliance, complaints, and pending approvals.")
@SecurityRequirement(name = "bearerAuth")
class OperationalHealthController {

    private final AdministrationService admin;

    OperationalHealthController(AdministrationService admin) {
        this.admin = admin;
    }

    @GetMapping("/health")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Operational health dashboard")
    public ApiResponse<OperationalHealth> health(@AuthenticationPrincipal AuthenticatedUser actor) {
        return ApiResponse.success(admin.health(actor));
    }
}

record BranchRequest(
        @NotBlank @Size(max = 32) String code,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 500) String addressLine,
        @Size(max = 120) String city,
        @Size(max = 120) String state,
        @Size(max = 2) String countryCode,
        @Size(max = 32) String phoneNumber,
        @Size(max = 255) String email,
        @Size(max = 32) String status) {

    Branch branch() {
        return new Branch(null, code, name, addressLine, city, state, countryCode, phoneNumber, email, status);
    }
}

record ConfigRequest(
        @NotBlank @Size(max = 150) String configKey,
        @Size(max = 4000) String configValue,
        @Size(max = 32) String valueType,
        @Size(max = 500) String description,
        boolean sensitive) {

    ConfigCommand command(String operation) {
        return new ConfigCommand(configKey, configValue, valueType, description, sensitive, operation);
    }
}

record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
}

record CalendarRequest(
        @NotBlank @Size(max = 32) String code,
        @NotBlank @Size(max = 128) String name,
        @Size(max = 64) String timezone,
        @Size(max = 64) String weekendDays,
        boolean active) {

    BusinessCalendar calendar() {
        return new BusinessCalendar(null, code, name, timezone, weekendDays, active);
    }
}

record HolidayRequest(LocalDate holidayDate, @NotBlank @Size(max = 128) String name) {
}

record FlagRequest(
        @NotBlank @Size(max = 100) String flagKey,
        boolean enabled,
        @Size(max = 500) String description,
        boolean sensitive) {
}
