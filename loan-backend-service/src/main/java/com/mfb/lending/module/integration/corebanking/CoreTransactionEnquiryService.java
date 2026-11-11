package com.mfb.lending.module.integration.corebanking;

import java.util.UUID;

import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.auth.dto.PageResponse;
import org.springframework.stereotype.Service;

@Service
public class CoreTransactionEnquiryService {

    private final CoreBankingStore store;
    private final CorePostingEngine engine;

    public CoreTransactionEnquiryService(CoreBankingStore store, CorePostingEngine engine) {
        this.store = store;
        this.engine = engine;
    }

    public PageResponse<CoreTransaction> list(String state, String kind, int page, int size) {
        String safeState = stateFilter(state);
        String safeKind = kindFilter(kind);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return new PageResponse<>(
                store.list(safeState, safeKind, safeSize, safePage * safeSize),
                safePage,
                safeSize,
                store.count(safeState, safeKind));
    }

    public CoreTransaction get(UUID id) {
        return store.require(id);
    }

    public CoreTransaction enquire(UUID id) {
        try {
            return engine.enquire(id);
        } catch (CoreTransportException ex) {
            throw CoreFailures.api(ex);
        }
    }

    public CorePostingEngine.ReconciliationResult reconcile(UUID id) {
        try {
            return engine.reconcile(id);
        } catch (CoreTransportException ex) {
            throw CoreFailures.api(ex);
        }
    }

    private static String stateFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return FinancialTransactionState.valueOf(value.trim()).name();
        } catch (IllegalArgumentException ex) {
            throw ValidationException.field("state", "Unknown state");
        }
    }

    private static String kindFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return FinancialTransactionKind.valueOf(value.trim()).name();
        } catch (IllegalArgumentException ex) {
            throw ValidationException.field("kind", "Unknown kind");
        }
    }
}
