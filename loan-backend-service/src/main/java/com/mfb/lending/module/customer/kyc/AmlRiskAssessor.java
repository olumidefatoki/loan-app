package com.mfb.lending.module.customer.kyc;

import java.math.BigDecimal;
import java.util.List;

/**
 * Provider port for an AML risk assessment. Adapters must not invent a vendor URL.
 * The mock assessment is a development stand-in, not a regulatory model.
 */
public interface AmlRiskAssessor {

    AmlDecision assess(List<String> names, BigDecimal incomeOrTurnover, BigDecimal expenses);
}
