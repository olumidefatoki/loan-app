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
    public static final String COLLATERAL_VERIFY = "COLLATERAL_VERIFY";
    public static final String REPORT_VIEW = "REPORT_VIEW";
    public static final String USER_MANAGE = "USER_MANAGE";
    public static final String SYSTEM_CONFIGURE = "SYSTEM_CONFIGURE";
    public static final String LOAN_PRODUCT_VIEW = "LOAN_PRODUCT_VIEW";
    public static final String LOAN_PRODUCT_MANAGE = "LOAN_PRODUCT_MANAGE";
    public static final String LOAN_PRODUCT_APPROVE = "LOAN_PRODUCT_APPROVE";

    public static final String CUSTOMER_ROLE = "CUSTOMER";
    public static final String SYSTEM_ADMIN_ROLE = "SYSTEM_ADMIN";

    private PermissionCodes() {
    }
}
