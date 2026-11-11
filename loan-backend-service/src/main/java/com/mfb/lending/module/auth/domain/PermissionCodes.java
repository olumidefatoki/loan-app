package com.mfb.lending.module.auth.domain;

public final class PermissionCodes {

    public static final String CUSTOMER_VIEW = "CUSTOMER_VIEW";
    public static final String CUSTOMER_CREATE = "CUSTOMER_CREATE";
    public static final String LOAN_APPLICATION_VIEW = "LOAN_APPLICATION_VIEW";
    public static final String LOAN_APPLICATION_CREATE = "LOAN_APPLICATION_CREATE";
    public static final String LOAN_APPROVE = "LOAN_APPROVE";
    public static final String LOAN_REJECT = "LOAN_REJECT";
    public static final String LOAN_DISBURSE = "LOAN_DISBURSE";
    public static final String REPAYMENT_VIEW = "REPAYMENT_VIEW";
    public static final String REPAYMENT_PROCESS = "REPAYMENT_PROCESS";
    public static final String COLLATERAL_VIEW = "COLLATERAL_VIEW";
    public static final String COLLATERAL_MANAGE = "COLLATERAL_MANAGE";
    public static final String COLLATERAL_VERIFY = "COLLATERAL_VERIFY";
    public static final String COLLATERAL_RELEASE = "COLLATERAL_RELEASE";
    public static final String GUARANTOR_VIEW = "GUARANTOR_VIEW";
    public static final String GUARANTOR_MANAGE = "GUARANTOR_MANAGE";
    public static final String GUARANTOR_VERIFY = "GUARANTOR_VERIFY";
    public static final String REPORT_VIEW = "REPORT_VIEW";
    public static final String USER_MANAGE = "USER_MANAGE";
    public static final String SYSTEM_CONFIGURE = "SYSTEM_CONFIGURE";
    public static final String LOAN_PRODUCT_VIEW = "LOAN_PRODUCT_VIEW";
    public static final String LOAN_PRODUCT_MANAGE = "LOAN_PRODUCT_MANAGE";
    public static final String LOAN_PRODUCT_APPROVE = "LOAN_PRODUCT_APPROVE";
    public static final String CREDIT_ASSESSMENT_VIEW = "CREDIT_ASSESSMENT_VIEW";
    public static final String CREDIT_ASSESSMENT_RUN = "CREDIT_ASSESSMENT_RUN";
    public static final String CREDIT_POLICY_MANAGE = "CREDIT_POLICY_MANAGE";

    public static final String CUSTOMER_ROLE = "CUSTOMER";
    public static final String SYSTEM_ADMIN_ROLE = "SYSTEM_ADMIN";

    private PermissionCodes() {
    }
}
