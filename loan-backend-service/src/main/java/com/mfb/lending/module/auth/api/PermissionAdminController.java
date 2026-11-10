package com.mfb.lending.module.auth.api;

import java.util.List;
import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.application.AdminPermissionService;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.dto.CreatePermissionRequest;
import com.mfb.lending.module.auth.dto.PermissionResponse;
import com.mfb.lending.module.auth.dto.UpdatePermissionRequest;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/permissions")
@Tag(name = "Permissions", description = "Database-backed permissions enforced on administration and, later, lending operations.")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAuthority('" + PermissionCodes.SYSTEM_CONFIGURE + "')")
public class PermissionAdminController {

    private final AdminPermissionService permissions;

    public PermissionAdminController(AdminPermissionService permissions) {
        this.permissions = permissions;
    }

    @GetMapping
    @Operation(summary = "List permissions")
    public ApiResponse<List<PermissionResponse>> list() {
        return ApiResponse.success(permissions.list());
    }

    @PostMapping
    @Operation(summary = "Create a permission")
    public ResponseEntity<ApiResponse<PermissionResponse>> create(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody CreatePermissionRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(permissions.create(request, actor, ClientContexts.from(httpRequest))));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a permission")
    public ApiResponse<PermissionResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(permissions.get(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a permission description")
    public ApiResponse<PermissionResponse> update(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePermissionRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(permissions.update(id, request, actor, ClientContexts.from(httpRequest)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a custom permission")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        permissions.delete(id, actor, ClientContexts.from(httpRequest));
        return ResponseEntity.noContent().build();
    }
}
