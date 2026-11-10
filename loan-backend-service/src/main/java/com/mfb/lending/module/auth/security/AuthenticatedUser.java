package com.mfb.lending.module.auth.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.domain.SecurityProfile;
import com.mfb.lending.module.auth.domain.UserRecord;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class AuthenticatedUser implements UserDetails {

    private final UUID id;
    private final UUID sessionId;
    private final String email;
    private final PrincipalType principalType;
    private final Set<String> permissions;
    private final Set<String> roles;
    private final Set<UUID> branchIds;
    private final boolean unrestrictedBranches;
    private final List<GrantedAuthority> authorities;

    private AuthenticatedUser(
            UUID id,
            UUID sessionId,
            String email,
            PrincipalType principalType,
            Set<String> permissions,
            Set<String> roles,
            Set<UUID> branchIds,
            boolean unrestrictedBranches) {
        this.id = id;
        this.sessionId = sessionId;
        this.email = email;
        this.principalType = principalType;
        this.permissions = permissions;
        this.roles = roles;
        this.branchIds = branchIds;
        this.unrestrictedBranches = unrestrictedBranches;
        List<GrantedAuthority> granted = new ArrayList<>();
        permissions.forEach(permission -> granted.add(new SimpleGrantedAuthority(permission)));
        roles.forEach(role -> granted.add(new SimpleGrantedAuthority("ROLE_" + role)));
        this.authorities = List.copyOf(granted);
    }

    public static AuthenticatedUser from(UserRecord user, UUID sessionId, SecurityProfile profile) {
        return new AuthenticatedUser(
                user.id(),
                sessionId,
                user.email(),
                user.principalType(),
                Set.copyOf(profile.permissions()),
                Set.copyOf(profile.roles()),
                Set.copyOf(profile.branchIds()),
                profile.unrestrictedBranches());
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public PrincipalType getPrincipalType() {
        return principalType;
    }

    public Set<String> permissions() {
        return permissions;
    }

    public Set<String> roles() {
        return roles;
    }

    public Set<UUID> branchIds() {
        return branchIds;
    }

    public boolean unrestrictedBranches() {
        return unrestrictedBranches;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return "";
    }

    @Override
    public String getUsername() {
        return email;
    }
}
