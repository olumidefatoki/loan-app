package com.mfb.lending.config;

import java.math.BigDecimal;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.offer")
public class OfferProperties implements InitializingBean {

    private int expiryDays = 14;
    private BigDecimal feeTaxRate = BigDecimal.ZERO;
    private final Signature signature = new Signature();
    private final Documents documents = new Documents();

    @Override
    public void afterPropertiesSet() {
        if (expiryDays < 1 || expiryDays > 365) {
            throw new IllegalStateException("app.offer.expiry-days must be between 1 and 365");
        }
        if (feeTaxRate == null || feeTaxRate.signum() < 0 || feeTaxRate.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalStateException("app.offer.fee-tax-rate must be between 0 and 100");
        }
        if (documents.directory == null || documents.directory.isBlank()) {
            throw new IllegalStateException("app.offer.documents.directory is required");
        }
        if (signature.mode == null) {
            throw new IllegalStateException("app.offer.signature.mode must be RECORDED_ACCEPTANCE or EXTERNAL");
        }
    }

    public int getExpiryDays() {
        return expiryDays;
    }

    public void setExpiryDays(int expiryDays) {
        this.expiryDays = expiryDays;
    }

    public BigDecimal getFeeTaxRate() {
        return feeTaxRate;
    }

    public void setFeeTaxRate(BigDecimal feeTaxRate) {
        this.feeTaxRate = feeTaxRate;
    }

    public Signature getSignature() {
        return signature;
    }

    public Documents getDocuments() {
        return documents;
    }

    public enum SignatureMode {
        RECORDED_ACCEPTANCE,
        EXTERNAL
    }

    public static class Signature {

        private SignatureMode mode = SignatureMode.RECORDED_ACCEPTANCE;

        public SignatureMode getMode() {
            return mode;
        }

        public void setMode(SignatureMode mode) {
            this.mode = mode;
        }
    }

    public static class Documents {

        private String directory;

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }
    }
}
