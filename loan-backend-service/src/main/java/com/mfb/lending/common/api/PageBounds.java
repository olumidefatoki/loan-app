package com.mfb.lending.common.api;

import com.mfb.lending.common.exception.ValidationException;

public record PageBounds(int page, int size, int offset) {

    public static PageBounds of(int page, int size) {
        if (page < 0) {
            throw ValidationException.field("page", "page must be zero or greater");
        }
        if (size < 1 || size > 100) {
            throw ValidationException.field("size", "size must be between 1 and 100");
        }
        return new PageBounds(page, size, page * size);
    }
}
