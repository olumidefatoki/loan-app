package com.mfb.lending.module.customer.kyc;

import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class MockSanctionsScreener implements SanctionsScreener {

    @Override
    public ScreeningDecision screen(List<String> names) {
        return MockKycDecisions.sanctions(names);
    }
}
