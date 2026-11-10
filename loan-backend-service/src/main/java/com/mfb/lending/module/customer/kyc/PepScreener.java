package com.mfb.lending.module.customer.kyc;

import java.util.List;

/**
 * Provider port for politically exposed person screening. Adapters must not invent a vendor URL.
 */
public interface PepScreener {

    ScreeningDecision screen(List<String> names);
}
