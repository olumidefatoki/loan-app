package com.mfb.lending.module.customer.domain;

import java.util.UUID;

public record AddressRecord(
        UUID id,
        UUID customerId,
        AddressType type,
        String line1,
        String line2,
        String city,
        String state,
        String countryCode,
        String postalCode
) {
}
