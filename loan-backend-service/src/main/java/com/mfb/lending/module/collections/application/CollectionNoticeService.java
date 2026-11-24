package com.mfb.lending.module.collections.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.config.CollectionsProperties;
import com.mfb.lending.module.collections.calculation.DelinquencyCalculator;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionNotification;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionPromise;
import com.mfb.lending.module.collections.domain.CollectionModels.DelinquencySnapshot;
import com.mfb.lending.module.collections.domain.CollectionModels.InstalmentFact;
import com.mfb.lending.module.collections.domain.CollectionModels.LoanPosition;
import com.mfb.lending.module.collections.domain.CollectionModels.MessageTemplate;
import com.mfb.lending.module.collections.domain.CollectionModels.PostedRepayment;
import com.mfb.lending.module.collections.persistence.CollectionsStore;
import org.springframework.stereotype.Service;

@Service
public class CollectionNoticeService {

    private final CollectionsStore store;
    private final ServicingExposureSource exposures;
    private final CollectionsProperties properties;
    private final java.time.Clock clock;

    public CollectionNoticeService(
            CollectionsStore store,
            ServicingExposureSource exposures,
            CollectionsProperties properties,
            java.time.Clock clock) {
        this.store = store;
        this.exposures = exposures;
        this.properties = properties;
        this.clock = clock;
    }

    public int prepare(LocalDate asOf) {
        int created = 0;
        LocalDate windowEnd = asOf.plusDays(Math.max(properties.getUpcomingDays(), 0));
        for (DelinquencySnapshot snapshot : store.snapshotsOn(asOf)) {
            LoanPosition position = exposures.require(snapshot.loanAccountId());
            for (InstalmentFact fact : position.instalments()) {
                BigDecimal unpaid = DelinquencyCalculator.unpaid(fact);
                if (unpaid.signum() == 0 || fact.dueDate() == null) {
                    continue;
                }
                if (fact.dueDate().isEqual(asOf)) {
                    created += notice(position, "DUE_DATE_REMINDER", asOf, fact.id(), null, null, fields(position, money(unpaid), fact.dueDate(), null, null, null, null));
                } else if (fact.dueDate().isAfter(asOf) && !fact.dueDate().isAfter(windowEnd)) {
                    created += notice(position, "UPCOMING_INSTALMENT", asOf, fact.id(), null, null, fields(position, money(unpaid), fact.dueDate(), null, null, null, null));
                } else if (fact.dueDate().isBefore(asOf)) {
                    int days = (int) ChronoUnit.DAYS.between(fact.dueDate(), asOf);
                    created += notice(position, "MISSED_REPAYMENT", asOf, fact.id(), null, null,
                            fields(position, money(unpaid), fact.dueDate(), Integer.toString(days), null, null, null));
                }
            }
            if (snapshot.overdue()) {
                created += notice(position, "OVERDUE_BALANCE", asOf, null, null, null,
                        fields(position, null, null, Integer.toString(snapshot.daysPastDue()), money(snapshot.overdueAmount()), null, null));
            }
            for (PostedRepayment repayment : position.postedRepayments()) {
                created += notice(position, "PAYMENT_CONFIRMATION", repayment.valueDate(), null, repayment.id(), null,
                        fields(position, money(repayment.amount()), repayment.valueDate(), null, null, null, null));
            }
        }
        return created;
    }

    public boolean followUp(LoanPosition position, CollectionPromise promise, LocalDate asOf) {
        return notice(position, "PAYMENT_FOLLOW_UP", asOf, null, null, null,
                fields(position, null, null, null, null, promise.promiseDate(), money(promise.amount()))) == 1;
    }

    public void restructuringDecision(LoanPosition position, UUID restructuringId, String decision, LocalDate asOf) {
        notice(position, "RESTRUCTURING_DECISION", asOf, null, null, restructuringId,
                fields(position, null, null, null, null, null, null, decision));
    }

    public int dispatch() {
        int sent = 0;
        for (int batch = 0; batch < 50; batch++) {
            List<CollectionNotification> pending = store.pendingNotifications(100);
            if (pending.isEmpty()) {
                return sent;
            }
            for (CollectionNotification notification : pending) {
                MessageTemplate template = store.template(notification.templateCode()).orElse(null);
                if (template == null || !template.active()) {
                    store.markNotification(notification.id(), "SKIPPED", null);
                    continue;
                }
                try {
                    CollectionMessages.render(template.body(), CollectionMessages.read(notification.payload()));
                    store.markNotification(notification.id(), "SENT", Instant.now(clock));
                    sent++;
                } catch (ConflictException ex) {
                    store.markNotification(notification.id(), "SKIPPED", null);
                }
            }
        }
        return sent;
    }

    private int notice(
            LoanPosition position,
            String templateCode,
            LocalDate businessDate,
            UUID scheduleItemId,
            UUID repaymentId,
            UUID restructuringId,
            Map<String, String> values) {
        boolean inserted = store.insertNotification(new CollectionNotification(
                UUID.randomUUID(), position.loanAccountId(), position.customerId(), templateCode, businessDate,
                scheduleItemId, repaymentId, restructuringId, CollectionMessages.payload(values), "PENDING", Instant.now(clock), null));
        return inserted ? 1 : 0;
    }

    private static Map<String, String> fields(
            LoanPosition position,
            String amount,
            LocalDate dueDate,
            String daysPastDue,
            String overdueAmount,
            LocalDate promiseDate,
            String promiseAmount) {
        return fields(position, amount, dueDate, daysPastDue, overdueAmount, promiseDate, promiseAmount, null);
    }

    private static Map<String, String> fields(
            LoanPosition position,
            String amount,
            LocalDate dueDate,
            String daysPastDue,
            String overdueAmount,
            LocalDate promiseDate,
            String promiseAmount,
            String decision) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("loanReference", position.reference());
        if (amount != null) {
            values.put("amount", amount);
        }
        if (dueDate != null) {
            values.put("dueDate", dueDate.toString());
        }
        if (daysPastDue != null) {
            values.put("daysPastDue", daysPastDue);
        }
        if (overdueAmount != null) {
            values.put("overdueAmount", overdueAmount);
        }
        if (promiseDate != null) {
            values.put("promiseDate", promiseDate.toString());
        }
        if (promiseAmount != null) {
            values.put("promiseAmount", promiseAmount);
        }
        if (decision != null) {
            values.put("decision", decision);
        }
        return values;
    }

    private static String money(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }
}
