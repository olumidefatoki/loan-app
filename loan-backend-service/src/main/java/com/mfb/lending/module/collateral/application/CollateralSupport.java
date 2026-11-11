package com.mfb.lending.module.collateral.application;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;

import com.mfb.lending.common.exception.ApiException;
import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ErrorCode;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.collateral.domain.VerificationStatus;
import com.mfb.lending.module.collateral.dto.InsuranceRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

final class CollateralSupport {

    private CollateralSupport() {
    }

    static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    static String text(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static void mutable(VerificationStatus status) {
        if (status == VerificationStatus.REJECTED || status == VerificationStatus.RELEASED) {
            throw new ConflictException("Collateral can no longer be changed");
        }
    }

    static void insuranceDates(InsuranceRequest insurance) {
        if (insurance.expiryDate().isBefore(insurance.startDate())) {
            throw ValidationException.field("insurance.expiryDate", "Insurance expiry cannot be before the start date");
        }
    }

    static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    static byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.DOCUMENT_REJECTED, HttpStatus.BAD_REQUEST, "A document is required");
        }
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.DOCUMENT_REJECTED, HttpStatus.BAD_REQUEST, "The document could not be read");
        }
    }
}
