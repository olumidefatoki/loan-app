package com.mfb.lending.common.validation;

import java.math.BigDecimal;
import java.util.UUID;

import com.mfb.lending.common.exception.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidationUtilsTest {

    @Test
    void requireTextTrimsAndRejectsBlankOrTooLongValues() {
        assertThat(ValidationUtils.requireText("  HQ  ", "code", 8)).isEqualTo("HQ");

        assertThatThrownBy(() -> ValidationUtils.requireText("   ", "code", 8))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("violations", singleViolation("code", "code is required"));

        assertThatThrownBy(() -> ValidationUtils.requireText("TOO-LONG", "code", 3))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void requirePositiveRejectsMissingAndNonPositiveAmounts() {
        assertThat(ValidationUtils.requirePositive(new BigDecimal("0.01"), "amount")).isEqualByComparingTo("0.01");

        assertThatThrownBy(() -> ValidationUtils.requirePositive(null, "amount"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> ValidationUtils.requirePositive(BigDecimal.ZERO, "amount"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> ValidationUtils.requirePositive(new BigDecimal("-1"), "amount"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void requireUuidAndEmailAcceptOnlyValidValues() {
        UUID id = UUID.fromString("6f4c2d1a-6a0e-4c1b-9c1a-0d5a6e7b8c9d");
        assertThat(ValidationUtils.requireUuid(id.toString(), "id")).isEqualTo(id);
        assertThat(ValidationUtils.requireEmail("ops@mfb.example", "email")).isEqualTo("ops@mfb.example");

        assertThatThrownBy(() -> ValidationUtils.requireUuid("not-a-uuid", "id"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> ValidationUtils.requireEmail("not-an-email", "email"))
                .isInstanceOf(ValidationException.class);
    }

    private static Object singleViolation(String field, String message) {
        return java.util.List.of(new com.mfb.lending.common.api.FieldViolation(field, message));
    }
}
