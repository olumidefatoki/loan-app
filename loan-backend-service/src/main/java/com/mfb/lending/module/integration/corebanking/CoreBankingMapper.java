package com.mfb.lending.module.integration.corebanking;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class CoreBankingMapper {

    private final ObjectMapper objectMapper;

    public CoreBankingMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CoreBankingClient.CoreTransportRequest request(
            CoreOperation operation,
            String idempotencyKey,
            String transactionReference,
            String correlationId,
            Map<String, Object> attributes) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (attributes != null) {
            attributes.forEach((key, value) -> {
                if (value != null) {
                    safe.put(key, value);
                }
            });
        }
        return new CoreBankingClient.CoreTransportRequest(
                operation,
                idempotencyKey,
                transactionReference,
                correlationId,
                Map.copyOf(safe));
    }

    public <T> CoreBankingAdapter.AdapterResult<T> result(
            CoreBankingClient.CoreTransportResponse response,
            T body) {
        return new CoreBankingAdapter.AdapterResult<>(
                response.outcome(),
                response.providerReference(),
                response.message(),
                body);
    }

    public CoreBankingAdapter.CustomerView customer(CoreBankingClient.CoreTransportResponse response) {
        Map<String, Object> attributes = response.attributes();
        return new CoreBankingAdapter.CustomerView(
                text(attributes, "customerReference"),
                bool(attributes, "verified"),
                text(attributes, "displayName"));
    }

    public CoreBankingAdapter.AccountView account(CoreBankingClient.CoreTransportResponse response) {
        Map<String, Object> attributes = response.attributes();
        return new CoreBankingAdapter.AccountView(
                text(attributes, "accountReference"),
                bool(attributes, "valid"),
                text(attributes, "status"),
                text(attributes, "maskedNumber"));
    }

    public CoreBankingAdapter.AccountList accounts(CoreBankingClient.CoreTransportResponse response) {
        Object raw = response.attributes().get("accounts");
        List<CoreBankingAdapter.AccountView> accounts = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> row = objectMap(item);
                accounts.add(new CoreBankingAdapter.AccountView(
                        text(row, "accountReference"),
                        bool(row, "valid"),
                        text(row, "status"),
                        text(row, "maskedNumber")));
            }
        }
        return new CoreBankingAdapter.AccountList(List.copyOf(accounts));
    }

    public CoreBankingAdapter.BalanceView balance(CoreBankingClient.CoreTransportResponse response) {
        Map<String, Object> attributes = response.attributes();
        return new CoreBankingAdapter.BalanceView(
                text(attributes, "accountReference"),
                money(attributes.get("amount")),
                text(attributes, "currencyCode"),
                text(attributes, "status"));
    }

    public CoreBankingAdapter.LoanAccountView loanAccount(CoreBankingClient.CoreTransportResponse response) {
        return new CoreBankingAdapter.LoanAccountView(
                firstText(response, "loanAccountReference"),
                text(response.attributes(), "status"));
    }

    public CoreBankingAdapter.PostingView posting(CoreBankingClient.CoreTransportResponse response) {
        Map<String, Object> attributes = response.attributes();
        boolean reversed = bool(attributes, "reversed");
        return new CoreBankingAdapter.PostingView(
                response.providerReference(),
                (bool(attributes, "posted") || response.outcome() == CoreOutcome.SUCCESSFUL) && !reversed,
                reversed,
                money(attributes.get("amount")),
                text(attributes, "currencyCode"));
    }

    public CoreBankingAdapter.PostingList postings(CoreBankingClient.CoreTransportResponse response) {
        Object raw = response.attributes().get("lines");
        List<CoreBankingAdapter.PostingLine> lines = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> row = objectMap(item);
                lines.add(new CoreBankingAdapter.PostingLine(
                        text(row, "reference"),
                        money(row.get("amount")),
                        text(row, "currencyCode"),
                        text(row, "direction")));
            }
        }
        return new CoreBankingAdapter.PostingList(List.copyOf(lines));
    }

    public String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Core banking payload could not be written", ex);
        }
    }

    private String firstText(CoreBankingClient.CoreTransportResponse response, String key) {
        String value = text(response.attributes(), key);
        return value == null ? response.providerReference() : value;
    }

    private static String text(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        return value == null ? null : value.toString();
    }

    private static boolean bool(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        return value instanceof Boolean flag && flag;
    }

    private static BigDecimal money(Object value) {
        if (value == null) {
            return null;
        }
        return new BigDecimal(value.toString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), item));
            return copy;
        }
        return objectMapper.convertValue(value, Map.class);
    }
}
