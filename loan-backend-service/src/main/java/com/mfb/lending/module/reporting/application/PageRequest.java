package com.mfb.lending.module.reporting.application;

import com.mfb.lending.common.exception.ValidationException;

public record PageRequest(int page, int size, int offset) {

    public static PageRequest of(Integer page, Integer size) {
        int resolvedPage = page == null ? 0 : page;
        int resolvedSize = size == null ? 20 : size;
        if (resolvedPage < 0 || resolvedPage > 500) {
            throw ValidationException.field("page", "Page must be between 0 and 500");
        }
        if (resolvedSize < 1 || resolvedSize > 100) {
            throw ValidationException.field("size", "Page size must be between 1 and 100");
        }
        return new PageRequest(resolvedPage, resolvedSize, resolvedPage * resolvedSize);
    }
}
