package com.mfb.lending.module.customer.application;

import java.util.UUID;

public interface ScreeningAlertSink {

    void record(UUID customerId, UUID screeningId, String screeningType, String outcome, String summary);
}
