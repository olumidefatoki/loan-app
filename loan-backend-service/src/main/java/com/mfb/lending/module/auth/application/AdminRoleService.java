package com.mfb.lending.module.auth.application;

import java.util.HashSet;
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
import com.mfb.lending.module.auth.domain.RoleRecord;
import com.mfb.lending.module.auth.dto.CreateRoleRequest;
import com.mfb.lending.module.auth.dto.RoleResponse;
import com.mfb.lending.module.auth.dto.UpdateRoleRequest;
import com.mfb.lending.module.auth.persistence.PermissionRepository;
import com.mfb.lending.module.auth.persistence.RoleRepository;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AdminRoleService {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final AuditService audit;
    private final TransactionTemplate transactions;

    public AdminRoleService(
            RoleRepository roles,
            PermissionRepository permissions,
            AuditService audit,
            TransactionTemplate transactions) {
        this.roles = roles;
        this.permissions = permissions;
        this.audit = audit;
        this.transactions = transactions;
    }

    public List<RoleResponse> list() {
        return roles.findAll().stream().map(this::toResponse).toList();
    }

    public RoleResponse get(UUID id) {
        return toResponse(require(id));
    }

    public RoleResponse create(CreateRoleRequest request, AuthenticatedUser actor, ClientContext client) {
        String code = normalizeCode(request.code());
        return transactions.execute(status -> {
            if (roles.findByCode(code).isPresent()) {
                throw new ConflictException("A role with this code already exists");
            }
            List<PermissionRecord> selected = requirePermissions(request.permissionCodes());
            RoleRecord role = new RoleRecord(
                    UUID.randomUUID(),
                    code,
                    request.name().trim(),
                    request.description(),
                    request.requiresMfa(),
                    request.unrestrictedBranches(),
                    false);
            roles.insert(role);
            roles.replacePermissions(role.id(), selected.stream().map(PermissionRecord::id).toList());
            audit.record("ROLE_CREATED", "role", role.id().toString(), actor.getId().toString(), client, Map.of("code", code));
            return toResponse(role);
        });
    }

    public RoleResponse update(UUID id, UpdateRoleRequest request, AuthenticatedUser actor, ClientContext client) {
        return transactions.execute(status -> {
            RoleRecord current = require(id);
            List<PermissionRecord> selected = request.permissionCodes() == null
                    ? List.of()
                    : requirePermissions(request.permissionCodes());
            if (current.systemRole()) {
                boolean permissionsChange = request.permissionCodes() != null
                        && !new HashSet<>(roles.permissionCodes(id)).equals(new HashSet<>(selected.stream().map(PermissionRecord::code).toList()));
                if (permissionsChange || current.requiresMfa() != request.requiresMfa()
                        || current.unrestrictedBranches() != request.unrestrictedBranches()) {
                    throw new ConflictException("System roles cannot change their permissions or access rules");
                }
            }
            RoleRecord updated = new RoleRecord(
                    current.id(),
                    current.code(),
                    request.name().trim(),
                    request.description(),
                    current.systemRole() ? current.requiresMfa() : request.requiresMfa(),
                    current.systemRole() ? current.unrestrictedBranches() : request.unrestrictedBranches(),
                    current.systemRole());
            roles.update(updated);
            if (!current.systemRole() && request.permissionCodes() != null) {
                roles.replacePermissions(id, selected.stream().map(PermissionRecord::id).toList());
            }
            audit.record("ROLE_UPDATED", "role", id.toString(), actor.getId().toString(), client, Map.of("code", current.code()));
            return toResponse(updated);
        });
    }

    public void delete(UUID id, AuthenticatedUser actor, ClientContext client) {
        transactions.executeWithoutResult(status -> {
            RoleRecord role = require(id);
            if (role.systemRole()) {
                throw new ConflictException("System roles cannot be deleted");
            }
            if (roles.assignedToAnyUser(id)) {
                throw new ConflictException("The role is still assigned to users");
            }
            roles.delete(id);
            audit.record("ROLE_DELETED", "role", id.toString(), actor.getId().toString(), client, Map.of("code", role.code()));
        });
    }

    private RoleResponse toResponse(RoleRecord role) {
        return new RoleResponse(
                role.id(),
                role.code(),
                role.name(),
                role.description(),
                role.requiresMfa(),
                role.unrestrictedBranches(),
                role.systemRole(),
                roles.permissionCodes(role.id()));
    }

    private RoleRecord require(UUID id) {
        return roles.findById(id).orElseThrow(() -> new ResourceNotFoundException("Role"));
    }

    private List<PermissionRecord> requirePermissions(List<String> codes) {
        List<String> normalized = codes.stream().map(code -> code.trim().toUpperCase(Locale.ROOT)).distinct().toList();
        List<PermissionRecord> selected = permissions.findByCodes(normalized);
        if (selected.size() != normalized.size()) {
            throw ValidationException.field("permissionCodes", "One or more permissions do not exist");
        }
        return selected;
    }

    private static String normalizeCode(String code) {
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) {
            throw ValidationException.field("code", "code must be uppercase letters, digits, or underscores");
        }
        return normalized;
    }
}
