package com.mfb.lending.module.loanoffer;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.loanoffer.application.OfferRules;
import com.mfb.lending.module.loanoffer.application.PdfAgreementWriter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfferRulesTest {

    @Test
    void principalCannotExceedTheApprovedAmount() {
        assertThat(OfferRules.principal(null, new BigDecimal("120000.00"), new BigDecimal("100000.00"), 2, RoundingMode.HALF_UP))
                .isEqualByComparingTo("100000.00");
        assertThat(OfferRules.principal(new BigDecimal("90000"), new BigDecimal("120000.00"), new BigDecimal("100000.00"), 2, RoundingMode.HALF_UP))
                .isEqualByComparingTo("90000.00");
        assertThatThrownBy(() -> OfferRules.principal(
                new BigDecimal("100000.01"), new BigDecimal("120000.00"), new BigDecimal("100000.00"), 2, RoundingMode.HALF_UP))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> OfferRules.principal(null, new BigDecimal("100.00"), null, 2, RoundingMode.HALF_UP))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void agreementPdfContainsTheRenderedText() {
        byte[] pdf = PdfAgreementWriter.write("Loan agreement LA000000000001", "Ada Okonkwo owes 120000.00");
        String source = new String(pdf, java.nio.charset.StandardCharsets.US_ASCII);
        assertThat(source).startsWith("%PDF-1.4");
        assertThat(source).contains("Ada Okonkwo owes 120000.00");
        assertThat(source).contains("%%EOF");
    }
}
