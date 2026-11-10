package com.mfb.lending.module.auth.dto;

public record MfaSetup(String secret, String otpauthUri) {
}
