package com.mfb.lending.module.loanoffer.application;

public final class SignatureStatements {

    public static final String RECORDED_ACCEPTANCE = """
            This acceptance is an electronic record that the customer read this agreement version and that their identity was verified on the customer KYC record. \
            It is not a qualified electronic signature, and it is not represented as legally equivalent to a wet-ink or certified digital signature, \
            unless the institution has configured an approved signature provider and obtained the required legal approval.""";

    public static final String PROVIDER_REQUIRED = """
            An approved electronic signature provider is not configured. \
            A checkbox or recorded acceptance will not be stored as a digital signature.""";

    private SignatureStatements() {
    }
}
