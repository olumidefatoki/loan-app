package com.mfb.lending.module.auth.dto;

import java.util.List;
import java.util.UUID;

public record RegistrationResponse(UUID userId, String status, List<String> verificationRequired) {
}
