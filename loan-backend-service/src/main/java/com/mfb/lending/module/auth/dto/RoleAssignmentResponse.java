package com.mfb.lending.module.auth.dto;

public record RoleAssignmentResponse(UserResponse user, MfaSetup mfaSetup) {
}
