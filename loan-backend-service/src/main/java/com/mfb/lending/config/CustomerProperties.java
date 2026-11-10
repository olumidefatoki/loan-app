package com.mfb.lending.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.beans.factory.InitializingBean;

@ConfigurationProperties(prefix = "app.customer")
public class CustomerProperties implements InitializingBean {

    private final Documents documents = new Documents();
    private final Kyc kyc = new Kyc();

    @Override
    public void afterPropertiesSet() {
        if (documents.directory == null || documents.directory.isBlank()) {
            throw new IllegalStateException("app.customer.documents.directory is required");
        }
        if (documents.maxBytes < 1024 || documents.maxBytes > 20L * 1024 * 1024) {
            throw new IllegalStateException("app.customer.documents.max-bytes must be between 1KB and 20MB");
        }
        if (kyc.provider == null || !kyc.provider.equals("mock")) {
            throw new IllegalStateException(
                    "app.customer.kyc.provider must be mock. This service does not call a vendor KYC endpoint.");
        }
    }

    public Documents getDocuments() {
        return documents;
    }

    public Kyc getKyc() {
        return kyc;
    }

    public static class Documents {

        private String directory;
        private long maxBytes = 10_485_760L;

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }
    }

    public static class Kyc {

        private String provider = "mock";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }
    }
}
