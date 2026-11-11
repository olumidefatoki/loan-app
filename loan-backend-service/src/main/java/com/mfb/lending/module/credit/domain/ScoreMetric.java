package com.mfb.lending.module.credit.domain;

public enum ScoreMetric {
    DTI,
    DISPOSABLE_INCOME_RATIO,
    EMPLOYMENT_MONTHS,
    TURNOVER_TO_INCOME,
    REPAYMENT_HISTORY,
    BUREAU_SCORE,
    DSCR,
    CASH_FLOW_MARGIN,
    BUSINESS_AGE_MONTHS,
    ACCOUNT_TURNOVER_TO_REVENUE,
    SECTOR,
    BUREAU_EXPOSURE_RATIO,
    COLLATERAL_COVERAGE;

    public boolean categorical() {
        return this == REPAYMENT_HISTORY || this == SECTOR;
    }
}
