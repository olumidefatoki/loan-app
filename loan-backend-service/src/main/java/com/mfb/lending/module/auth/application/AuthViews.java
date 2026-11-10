package com.mfb.lending.module.auth.application;

import java.util.HashSet;

import com.mfb.lending.module.auth.domain.SecurityProfile;
import com.mfb.lending.module.auth.domain.SessionRecord;
import com.mfb.lending.module.auth.domain.UserRecord;
import com.mfb.lending.module.auth.dto.SessionResponse;
import com.mfb.lending.module.auth.dto.UserResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;

final class AuthViews {

    private AuthViews() {
    }

    static UserResponse user(UserRecord user, SecurityProfile profile) {
        return new UserResponse(
                user.id(),
                user.principalType().name(),
                user.customerType().name(),
                user.fullName(),
                user.businessName(),
                user.email(),
                user.phoneNumber(),
                user.status().name(),
                user.emailVerified(),
                user.phoneVerified(),
                user.mfaEnabled(),
                profile.roles(),
                profile.permissions(),
                profile.branchIds(),
                user.passwordExpiresAt());
    }

    static SessionResponse session(SessionRecord session) {
        return new SessionResponse(
                session.id(),
                session.status(),
                session.ipAddress(),
                session.userAgent(),
                session.createdAt(),
                session.lastSeenAt(),
                session.expiresAt(),
                session.revokedAt(),
                session.revokedReason());
    }

    static AccessPolicy.Actor actor(AuthenticatedUser user) {
        return new AccessPolicy.Actor(user.getId(), user.permissions(), user.branchIds(), user.unrestrictedBranches());
    }

    static AccessPolicy.Subject subject(UserRecord user, SecurityProfile profile) {
        return new AccessPolicy.Subject(user.id(), new HashSet<>(profile.branchIds()));
    }
}
