package com.mfb.lending.module.auth.api;

import java.util.List;
import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.application.AdminUserService;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.dto.AssignRolesRequest;
import com.mfb.lending.module.auth.dto.CreateStaffRequest;
import com.mfb.lending.module.auth.dto.CreateUserResponse;
import com.mfb.lending.module.auth.dto.MessageResponse;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.dto.RoleAssignmentResponse;
import com.mfb.lending.module.auth.dto.SessionResponse;
import com.mfb.lending.module.auth.dto.UpdateUserRequest;
import com.mfb.lending.module.auth.dto.UserResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
@Tag(name = "User administration", description = "Staff and customer account administration. Results are limited to the caller's branches unless the caller has unrestricted branch access.")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAuthority('" + PermissionCodes.USER_MANAGE + "')")
public class UserAdminController {

    private final AdminUserService users;

    public UserAdminController(AdminUserService users) {
        this.users = users;
    }

    @GetMapping
    @Operation(summary = "List accounts visible to the caller")
    public ApiResponse<PageResponse<UserResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) String principalType,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(users.list(actor, principalType, branchId, page, size));
    }

    @PostMapping
    @Operation(summary = "Create a staff account")
    public ResponseEntity<ApiResponse<CreateUserResponse>> create(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody CreateStaffRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(users.create(request, actor, ClientContexts.from(httpRequest))));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an account when the caller is allowed to see it")
    public ApiResponse<UserResponse> get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(users.get(actor, id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an account, including activation and deactivation")
    public ApiResponse<UserResponse> update(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(users.update(id, request, actor, ClientContexts.from(httpRequest)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Disable an account and revoke its sessions")
    public ApiResponse<UserResponse> delete(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(users.disable(id, actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/{id}/roles")
    @Operation(summary = "Replace the roles assigned to an account")
    public ApiResponse<RoleAssignmentResponse> assignRoles(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody AssignRolesRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(users.assignRoles(id, request, actor, ClientContexts.from(httpRequest)));
    }

    @GetMapping("/{id}/sessions")
    @Operation(summary = "List recent sessions for an account")
    public ApiResponse<List<SessionResponse>> sessions(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(users.sessions(actor, id));
    }

    @PostMapping("/{id}/revoke-sessions")
    @Operation(summary = "Revoke every active session for an account")
    public ApiResponse<MessageResponse> revokeSessions(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(users.revokeSessions(id, actor, ClientContexts.from(httpRequest)));
    }
}
