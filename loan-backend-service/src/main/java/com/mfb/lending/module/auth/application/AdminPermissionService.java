package com.mfb.lending.module.auth.application;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.domain.PermissionRecord;
import com.mfb.lending.module.auth.dto.CreatePermissionRequest;
import com.mfb.lending.module.auth.dto.PermissionResponse;
import com.mfb.lending.module.auth.dto.UpdatePermissionRequest;
import com.mfb.lending.module.auth.persistence.PermissionRepository;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AdminPermissionService {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

    private final PermissionRepository permissions;
    private final AuditService audit;
    private final TransactionTemplate transactions;

    public AdminPermissionService(PermissionRepository permissions, AuditService audit, TransactionTemplate transactions) {
        this.permissions = permissions;
        this.audit = audit;
        this.transactions = transactions;
    }

    public List<PermissionResponse> list() {
        return permissions.findAll().stream().map(AdminPermissionService::toResponse).toList();
    }

    public PermissionResponse get(UUID id) {
        return toResponse(require(id));
    }

    public PermissionResponse create(CreatePermissionRequest request, AuthenticatedUser actor, ClientContext client) {
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw ValidationException.field("code", "code must be uppercase letters, digits, or underscores");
        }
        return transactions.execute(status -> {
            if (permissions.findByCode(code).isPresent()) {
                throw new ConflictException("A permission with this code already exists");
            }
            PermissionRecord permission = new PermissionRecord(UUID.randomUUID(), code, request.description().trim(), false);
            permissions.insert(permission);
            audit.record("PERMISSION_CREATED", "permission", permission.id().toString(), actor.getId().toString(), client,
                    Map.of("code", code));
            return toResponse(permission);
        });
    }

    public PermissionResponse update(UUID id, UpdatePermissionRequest request, AuthenticatedUser actor, ClientContext client) {
        return transactions.execute(status -> {
            PermissionRecord current = require(id);
            permissions.updateDescription(id, request.description().trim());
            audit.record("PERMISSION_UPDATED", "permission", id.toString(), actor.getId().toString(), client,
                    Map.of("code", current.code()));
            return new PermissionResponse(current.id(), current.code(), request.description().trim(), current.systemPermission());
        });
    }

    public void delete(UUID id, AuthenticatedUser actor, ClientContext client) {
        transactions.executeWithoutResult(status -> {
            PermissionRecord permission = require(id);
            if (permission.systemPermission()) {
                throw new ConflictException("System permissions cannot be deleted");
            }
            if (permissions.assignedToAnyRole(id)) {
                throw new ConflictException("The permission is still assigned to a role");
            }
            permissions.delete(id);
            audit.record("PERMISSION_DELETED", "permission", id.toString(), actor.getId().toString(), client,
                    Map.of("code", permission.code()));
        });
    }

    private PermissionRecord require(UUID id) {
        return permissions.findById(id).orElseThrow(() -> new ResourceNotFoundException("Permission"));
    }

    private static PermissionResponse toResponse(PermissionRecord permission) {
        return new PermissionResponse(permission.id(), permission.code(), permission.description(), permission.systemPermission());
    }
}
