package com.mfb.lending.module.customer.persistence;

import com.mfb.lending.module.auth.domain.CustomerType;
import com.mfb.lending.module.auth.domain.PrincipalType;

public record LinkedUser(PrincipalType principalType, CustomerType customerType) {
}
