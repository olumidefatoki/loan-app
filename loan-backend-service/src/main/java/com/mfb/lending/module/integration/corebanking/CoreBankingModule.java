package com.mfb.lending.module.integration.corebanking;

/**
 * Provider-independent core banking integration.
 * Business modules call the services in this package. They do not call a vendor URL.
 * The HTTP client stays unused until operation paths are supplied for a real contract.
 * Development and test use the mock provider and do not post to a bank.
 */
public final class CoreBankingModule {

    static final String SOURCE = "core-banking";
    static final String OUTBOX_DESTINATION = "core-banking";

    private CoreBankingModule() {
    }
}
