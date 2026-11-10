package com.mfb.lending.module.auth.application;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.domain.RoleRecord;

public final class AccessPolicy {

    private AccessPolicy() {
    }

    public record Actor(UUID userId, Set<String> permissions, Set<UUID> branchIds, boolean unrestrictedBranches) {

        public Actor {
            permissions = Set.copyOf(permissions);
            branchIds = Set.copyOf(branchIds);
        }

        public boolean has(String permission) {
            return permissions.contains(permission);
        }
    }

    public record Subject(UUID userId, Set<UUID> branchIds) {

        public Subject {
            branchIds = Set.copyOf(branchIds);
        }
    }

    public static void assertCanView(Actor actor, Subject subject) {
        if (actor.userId().equals(subject.userId())) {
            return;
        }
        if (!actor.has(PermissionCodes.USER_MANAGE) && !actor.has(PermissionCodes.CUSTOMER_VIEW)) {
            throw new ForbiddenException("You do not have permission to view this account");
        }
        assertBranchOverlap(actor, subject);
    }

    public static void assertCanMutate(Actor actor, Subject subject) {
        if (actor.userId().equals(subject.userId())) {
            throw new ForbiddenException("You cannot administer your own account");
        }
        if (!actor.has(PermissionCodes.USER_MANAGE)) {
            throw new ForbiddenException("You do not have permission to administer this account");
        }
        assertBranchOverlap(actor, subject);
    }

    public static void assertCanAssign(Actor actor, Subject subject, Collection<RoleRecord> roles, Collection<String> rolePermissions) {
        assertCanMutate(actor, subject);
        for (RoleRecord role : roles) {
            if (role.unrestrictedBranches() && !actor.has(PermissionCodes.SYSTEM_CONFIGURE)) {
                throw new ForbiddenException("You cannot assign an unrestricted role");
            }
        }
        if (actor.has(PermissionCodes.SYSTEM_CONFIGURE)) {
            return;
        }
        for (String permission : rolePermissions) {
            if (!actor.has(permission)) {
                throw new ForbiddenException("You cannot grant a permission you do not hold");
            }
        }
    }

    public static void assertBranchAllowed(Actor actor, UUID branchId) {
        if (branchId == null || actor.unrestrictedBranches() || actor.branchIds().contains(branchId)) {
            return;
        }
        throw new ForbiddenException("You cannot access this branch");
    }

    private static void assertBranchOverlap(Actor actor, Subject subject) {
        if (actor.unrestrictedBranches()) {
            return;
        }
        if (subject.branchIds().isEmpty()) {
            throw new ForbiddenException("You cannot access an account outside your branches");
        }
        Set<UUID> overlap = new HashSet<>(actor.branchIds());
        overlap.retainAll(subject.branchIds());
        if (overlap.isEmpty()) {
            throw new ForbiddenException("You cannot access an account outside your branches");
        }
    }
}
