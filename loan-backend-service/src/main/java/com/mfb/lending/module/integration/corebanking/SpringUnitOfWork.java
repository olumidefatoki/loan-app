package com.mfb.lending.module.integration.corebanking;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class SpringUnitOfWork implements UnitOfWork {

    private final TransactionTemplate template;

    public SpringUnitOfWork(PlatformTransactionManager manager) {
        this.template = new TransactionTemplate(manager);
    }

    @Override
    public <T> T required(Supplier<T> work) {
        return template.execute(status -> work.get());
    }
}
