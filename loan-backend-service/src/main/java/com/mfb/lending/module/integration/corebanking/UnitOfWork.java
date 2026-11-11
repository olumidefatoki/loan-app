package com.mfb.lending.module.integration.corebanking;

import java.util.function.Supplier;

public interface UnitOfWork {

    <T> T required(Supplier<T> work);
}
