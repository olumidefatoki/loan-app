package com.mfb.lending.module.auth.api;

import java.util.List;
import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.application.AdminRoleService;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.dto.CreateRoleRequest;
import com.mfb.lending.module.auth.dto.RoleResponse;
import com.mfb.lending.module.auth.dto.UpdateRoleRequest;
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
@RequestMapping("/api/v1/admin/roles")
@Tag(name = "Roles", description = "Database-backed roles. System roles keep their permission and branch rules.")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAuthority('" + PermissionCodes.SYSTEM_CONFIGURE + "')")
public class RoleAdminController {

    private final AdminRoleService roles;

    public RoleAdminController(AdminRoleService roles) {
        this.roles = roles;
    }

    @GetMapping
    @Operation(summary = "List roles")
    public ApiResponse<List<RoleResponse>> list() {
        return ApiResponse.success(roles.list());
    }

    @PostMapping
    @Operation(summary = "Create a role")
    public ResponseEntity<ApiResponse<RoleResponse>> create(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody CreateRoleRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(roles.create(request, actor, ClientContexts.from(httpRequest))));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a role")
    public ApiResponse<RoleResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(roles.get(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a role")
    public ApiResponse<RoleResponse> update(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRoleRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(roles.update(id, request, actor, ClientContexts.from(httpRequest)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a custom role")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        roles.delete(id, actor, ClientContexts.from(httpRequest));
        return ResponseEntity.noContent().build();
    }
}
