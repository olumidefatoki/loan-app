package com.mfb.lending.module.customer.kyc;

import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class MockPepScreener implements PepScreener {

    @Override
    public ScreeningDecision screen(List<String> names) {
        return MockKycDecisions.pep(names);
    }
}
