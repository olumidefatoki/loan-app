package com.mfb.lending.module.servicing.application;

import java.util.UUID;

public interface LoanBookingSource {

    LoanBooking load(UUID applicationId, String coreReference);
}
