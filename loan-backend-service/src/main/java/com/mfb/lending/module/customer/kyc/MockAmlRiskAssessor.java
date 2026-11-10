package com.mfb.lending.module.customer.kyc;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class MockAmlRiskAssessor implements AmlRiskAssessor {

    @Override
    public AmlDecision assess(List<String> names, BigDecimal incomeOrTurnover, BigDecimal expenses) {
        return MockKycDecisions.aml(names, incomeOrTurnover, expenses);
    }
}
