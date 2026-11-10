package com.mfb.lending.module.system.dto;

public record VersionResponse(
        String name,
        String version,
        String javaVersion,
        String springBootVersion
) {
}
