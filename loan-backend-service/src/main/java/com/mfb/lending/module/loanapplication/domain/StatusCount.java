package com.mfb.lending.module.loanapplication.domain;

import java.math.BigDecimal;

public record StatusCount(ApplicationStatus status, long count, BigDecimal amount) {
}
