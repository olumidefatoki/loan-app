package com.mfb.lending.module.auth.dto;

public record CreateUserResponse(UserResponse user, MfaSetup mfaSetup) {
}
