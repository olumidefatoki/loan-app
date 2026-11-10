package com.mfb.lending.module.customer.kyc;

import java.util.List;

/**
 * Provider port for sanctions screening. Adapters must not invent a vendor URL.
 */
public interface SanctionsScreener {

    ScreeningDecision screen(List<String> names);
}
