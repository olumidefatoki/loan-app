package com.mfb.lending.module.customer.application;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ErrorCode;
import org.springframework.dao.DuplicateKeyException;

final class CustomerConflicts {

    private CustomerConflicts() {
    }

    static void assertDifferentCustomer(Optional<UUID> existingCustomerId, UUID customerId, String message) {
        if (existingCustomerId.isPresent() && !existingCustomerId.get().equals(customerId)) {
            throw new ConflictException(ErrorCode.DUPLICATE_CUSTOMER, message);
        }
    }

    static ConflictException translate(DuplicateKeyException exception) {
        String message = String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
        if (message.contains("director")) {
            return new ConflictException(ErrorCode.CONFLICT, "This director is already recorded");
        }
        if (message.contains("owner")) {
            return new ConflictException(ErrorCode.CONFLICT, "This beneficial owner is already recorded");
        }
        if (message.contains("bvn")) {
            return new ConflictException(ErrorCode.DUPLICATE_CUSTOMER, "A customer with this BVN is already registered");
        }
        if (message.contains("nin")) {
            return new ConflictException(ErrorCode.DUPLICATE_CUSTOMER, "A customer with this NIN is already registered");
        }
        if (message.contains("cac")) {
            return new ConflictException(ErrorCode.DUPLICATE_CUSTOMER, "A customer with this CAC registration number is already registered");
        }
        if (message.contains("tin")) {
            return new ConflictException(ErrorCode.DUPLICATE_CUSTOMER, "A customer with this tax identification number is already registered");
        }
        if (message.contains("email")) {
            return new ConflictException(ErrorCode.DUPLICATE_CUSTOMER, "A customer with this email is already registered");
        }
        if (message.contains("phone")) {
            return new ConflictException(ErrorCode.DUPLICATE_CUSTOMER, "A customer with this phone number is already registered");
        }
        if (message.contains("user")) {
            return new ConflictException(ErrorCode.DUPLICATE_CUSTOMER, "This account is already linked to a customer");
        }
        return new ConflictException(ErrorCode.DUPLICATE_CUSTOMER, "A matching customer already exists");
    }
}
