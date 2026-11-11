package com.mfb.lending.module.servicing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.integration.corebanking.UnitOfWork;
import com.mfb.lending.module.loancalculation.LoanCalculationEngine;
import com.mfb.lending.module.loancalculation.LoanCalculationEngine.LoanQuote;
import com.mfb.lending.module.loanproduct.domain.DayCountConvention;
import com.mfb.lending.module.loanproduct.domain.EarlyRepaymentRule;
import com.mfb.lending.module.loanproduct.domain.InterestMethod;
import com.mfb.lending.module.loanproduct.domain.InterestPeriod;
import com.mfb.lending.module.loanproduct.domain.PenaltyBasis;
import com.mfb.lending.module.loanproduct.domain.RepaymentFrequency;
import com.mfb.lending.module.loanproduct.domain.TenureUnit;
import com.mfb.lending.module.servicing.calculation.AccrualFrequency;
import com.mfb.lending.module.servicing.calculation.AccountingEngine;
import com.mfb.lending.module.servicing.calculation.AccountingEngine.Draft;
import com.mfb.lending.module.servicing.calculation.AccountingEngine.DraftLine;
import com.mfb.lending.module.servicing.calculation.AllocationComponent;
import com.mfb.lending.module.servicing.calculation.HolidayRule;
import com.mfb.lending.module.servicing.calculation.ServicingCalculator;
import com.mfb.lending.module.servicing.calculation.ServicingCalculator.AllocationResult;
import com.mfb.lending.module.servicing.calculation.ServicingCalculator.DailySlice;
import com.mfb.lending.module.servicing.calculation.ServicingCalculator.RecognisedInterest;
import com.mfb.lending.module.servicing.calculation.ServicingCalculator.ScheduleQuote;
import com.mfb.lending.module.servicing.calculation.ServicingCalculator.ScheduleRequest;
import com.mfb.lending.module.servicing.calculation.ServicingCalculator.ScheduleRow;
import com.mfb.lending.module.servicing.calculation.ServicingCalculator.SettlementQuote;
import com.mfb.lending.module.servicing.calculation.StandardChart.AllocationPolicy;
import com.mfb.lending.module.servicing.domain.AccountEvent;
import com.mfb.lending.module.servicing.domain.AccountingEvent;
import com.mfb.lending.module.servicing.domain.BalanceSnapshot;
import com.mfb.lending.module.servicing.domain.InterestAccrual;
import com.mfb.lending.module.servicing.domain.InterestAdjustment;
import com.mfb.lending.module.servicing.domain.JournalHeader;
import com.mfb.lending.module.servicing.domain.JournalLine;
import com.mfb.lending.module.servicing.domain.JournalStatus;
import com.mfb.lending.module.servicing.domain.LoanAccount;
import com.mfb.lending.module.servicing.domain.LoanAccount.Position;
import com.mfb.lending.module.servicing.domain.LoanAccountStatus;
import com.mfb.lending.module.servicing.domain.LoanCharge;
import com.mfb.lending.module.servicing.domain.LoanRepayment;
import com.mfb.lending.module.servicing.domain.RepaymentAllocation;
import com.mfb.lending.module.servicing.domain.RepaymentHistory;
import com.mfb.lending.module.servicing.domain.RepaymentStatus;
import com.mfb.lending.module.servicing.domain.ScheduleLine;
import com.mfb.lending.module.servicing.domain.ScheduleVersion;
import com.mfb.lending.module.servicing.domain.TransactionReversal;
import com.mfb.lending.module.servicing.persistence.ServicingStore;
import org.springframework.stereotype.Service;

@Service
public class LoanServicing {

    private static final ZoneId BANK = ZoneId.of("Africa/Lagos");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{8,64}");

    private final ServicingStore store;
    private final LoanBookingSource bookings;
    private final CoreCollectionPort core;
    private final UnitOfWork unitOfWork;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final java.time.Clock clock;

    public LoanServicing(
            ServicingStore store,
            LoanBookingSource bookings,
            CoreCollectionPort core,
            UnitOfWork unitOfWork,
            AuditService audit,
            ObjectMapper objectMapper,
            java.time.Clock clock) {
        this.store = store;
        this.bookings = bookings;
        this.core = core;
        this.unitOfWork = unitOfWork;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public LoanAccount activateFromDisbursement(UUID applicationId, String coreReference, UUID actorId, String actorName) {
        return unitOfWork.required(() -> {
            var existing = store.findByApplication(applicationId);
            if (existing.isPresent()) {
                return existing.get();
            }
            return openConfirmed(bookings.load(applicationId, coreReference), actorId, actorName);
        });
    }

    public LoanAccount openConfirmed(LoanBooking booking, UUID actorId, String actorName) {
        if (booking == null || !booking.disbursementConfirmed() || booking.coreReference() == null || booking.coreReference().isBlank()) {
            throw new ConflictException("A loan account can be activated only after core banking confirms disbursement");
        }
        return unitOfWork.required(() -> createAccount(booking, actorId, actorName));
    }

    private LoanAccount createAccount(LoanBooking booking, UUID actorId, String actorName) {
        var existing = store.findByApplication(booking.applicationId());
        if (existing.isPresent()) {
            return existing.get();
        }
        LocalDate disbursementDate = today();
        AccrualFrequency accrualFrequency = booking.frequency() == RepaymentFrequency.MONTHLY
                ? AccrualFrequency.MONTHLY
                : AccrualFrequency.DAILY;
        ScheduleQuote quote = ServicingCalculator.schedule(request(booking, disbursementDate, booking.gracePeriodDays(), booking.tenure(), booking.interestRate(), null, HolidayRule.NONE));
        LoanQuote figures = quote.quote();
        BigDecimal fees = figures.processingFees().add(figures.otherFees());
        if (figures.principal().compareTo(fees.add(figures.taxes()).add(figures.netDisbursement())) != 0) {
            throw new IllegalStateException("Disbursement journal would not balance");
        }
        Instant now = Instant.now(clock);
        UUID accountId = UUID.randomUUID();
        UUID scheduleId = UUID.randomUUID();
        LocalDate accrualStart = disbursementDate.plusDays(booking.gracePeriodDays());
        LocalDate maturity = quote.rows().get(quote.rows().size() - 1).dueDate();
        String parameters = parameters(booking, disbursementDate, accrualFrequency, quote);
        LoanAccount pending = new LoanAccount(
                accountId,
                store.nextAccountReference(),
                booking.applicationId(),
                booking.customerId(),
                booking.branchId(),
                booking.productId(),
                booking.productVersionId(),
                booking.offerId(),
                "NGN",
                LoanAccountStatus.PENDING_ACTIVATION,
                figures.principal(),
                figures.principal(),
                zero(booking.scale()),
                zero(booking.scale()),
                zero(booking.scale()),
                zero(booking.scale()),
                zero(booking.scale()),
                disbursementDate,
                maturity,
                accrualStart,
                accrualStart,
                booking.gracePeriodDays(),
                booking.penaltyGraceDays(),
                booking.penaltyBasis().name(),
                booking.penaltyRate() == null ? ServicingCalculator.zero(4) : booking.penaltyRate().setScale(4, java.math.RoundingMode.HALF_UP),
                money(booking.penaltyAmount(), booking.scale(), booking.rounding()),
                booking.earlyRule().name(),
                booking.earlyFeeRate(),
                booking.interestMethod().name(),
                booking.interestPeriod().name(),
                booking.interestRate(),
                booking.frequency().name(),
                booking.dayCount().name(),
                booking.scale(),
                booking.rounding().name(),
                accrualFrequency.name(),
                null,
                HolidayRule.NONE.name(),
                booking.coreReference(),
                scheduleId,
                1,
                0,
                now,
                null,
                now,
                now,
                actorName);
        try {
            store.insertAccount(pending);
        } catch (ConflictException ex) {
            return store.findByApplication(booking.applicationId()).orElseThrow(() -> ex);
        }
        store.insertSchedule(new ScheduleVersion(
                scheduleId, accountId, 1, "CURRENT", null, figures.engineVersion(), ServicingCalculator.VERSION,
                fingerprint(quote), parameters, "Opening schedule", now, actorName));
        store.insertLines(lines(scheduleId, quote, booking.scale()));
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        amounts.put("PRINCIPAL", figures.principal());
        amounts.put("FEE", fees);
        amounts.put("TAX", figures.taxes());
        amounts.put("NET_CASH", figures.netDisbursement());
        post(pending, "DISBURSEMENT", amounts, "Loan disbursement " + booking.coreReference(), actorId, null);
        store.insertEvent(event(accountId, "CREATED", "{\"status\":\"PENDING_ACTIVATION\"}", actorId, now));
        LoanAccount active = advance(pending, pending.position(), LoanAccountStatus.ACTIVE, pending.outstandingPrincipal(),
                pending.accruedInterest(), pending.outstandingInterest(), pending.outstandingFees(), pending.outstandingPenalties(),
                pending.overpayment(), maturity, accrualStart, accrualStart, scheduleId, 1, pending.interestRate(), null);
        store.updateAccount(active, pending.version());
        snapshot(active, disbursementDate, "ACTIVATED");
        store.insertEvent(event(accountId, "ACTIVATED", "{\"coreReference\":\"" + booking.coreReference() + "\"}", actorId, now));
        audit("LOAN_ACCOUNT_ACTIVATED", accountId, actorName, Map.of("reference", active.reference(), "coreReference", booking.coreReference()));
        return active;
    }

    public LoanAccount get(UUID id) {
        return store.findAccount(id).orElseThrow(() -> new ResourceNotFoundException("Loan account"));
    }

    public Page list(UUID customerId, String status, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return new Page(
                store.listAccounts(customerId, status, safeSize, safePage * safeSize),
                safePage,
                safeSize,
                store.countAccounts(customerId, status));
    }

    public ScheduleView schedule(UUID id, Integer version) {
        LoanAccount account = get(id);
        ScheduleVersion selected = store.findSchedule(id, version).orElseThrow(() -> new ResourceNotFoundException("Repayment schedule"));
        return new ScheduleView(account, selected, store.lines(selected.id()));
    }

    public StatementView statement(UUID id, LocalDate from, LocalDate to) {
        LoanAccount account = get(id);
        LocalDate start = from == null ? account.disbursementDate() : from;
        LocalDate end = to == null ? today() : to;
        if (end.isBefore(start)) {
            throw ValidationException.field("to", "Statement end date cannot be before the start date");
        }
        List<ScheduleLine> lines = store.lines(account.currentScheduleId());
        BigDecimal contractual = lines.stream().map(ScheduleLine::interestDue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paid = account.accruedInterest().subtract(account.outstandingInterest());
        BigDecimal unearned = contractual.subtract(account.accruedInterest());
        if (unearned.signum() < 0) {
            unearned = zero(account.scale());
        }
        return new StatementView(
                account,
                start,
                end,
                store.snapshotBefore(id, start).orElse(null),
                store.snapshots(id, start, end),
                store.events(id),
                store.repayments(id),
                contractual,
                paid,
                unearned);
    }

    public SettlementQuote settlementQuote(UUID id, LocalDate valueDate) {
        LoanAccount account = get(id);
        LocalDate asOf = valueDate(account, valueDate);
        return quotation(account, store.lines(account.currentScheduleId()), asOf);
    }

    public LoanRepayment repay(RepaymentCommand command) {
        validateKey(command.idempotencyKey());
        LoanRepayment existing = store.findRepaymentByKey(command.idempotencyKey()).orElse(null);
        LoanRepayment repayment = existing == null ? receive(command) : existing;
        return progress(repayment, command.actorId(), command.actorName());
    }

    public LoanRepayment settle(RepaymentCommand command) {
        return repay(new RepaymentCommand(
                command.loanAccountId(), command.amount(), command.channel(), command.idempotencyKey(), command.valueDate(),
                command.externalReference(), true, command.actorId(), command.actorName()));
    }

    public LoanRepayment reverse(UUID repaymentId, String idempotencyKey, UUID actorId, String actorName) {
        validateKey(idempotencyKey);
        if (actorId == null) {
            throw ValidationException.field("actorId", "An authorising officer is required");
        }
        LoanRepayment repayment = store.findRepayment(repaymentId).orElseThrow(() -> new ResourceNotFoundException("Repayment"));
        if (repayment.status() == RepaymentStatus.REVERSED) {
            return repayment;
        }
        if (repayment.status() != RepaymentStatus.POSTED && repayment.status() != RepaymentStatus.REVERSAL_PENDING) {
            throw new ConflictException("Only a confirmed repayment can be reversed");
        }
        if (repayment.coreTransactionId() == null) {
            throw new ConflictException("The repayment has no confirmed core banking transaction");
        }
        if (repayment.status() == RepaymentStatus.POSTED) {
            unitOfWork.required(() -> store.lockAccount(repayment.loanAccountId(), () -> {
                LoanRepayment current = store.findRepayment(repaymentId).orElseThrow();
                if (current.status() == RepaymentStatus.POSTED) {
                    store.updateRepayment(copy(current, RepaymentStatus.REVERSAL_PENDING, current.failureReason(), current.cashJournalId(), current.feeJournalId(), current.feeCharged(), current.coreTransactionId(), current.coreReference()), current.version());
                    history(current, RepaymentStatus.REVERSAL_PENDING, "Reversal requested");
                }
                return current;
            }));
        }
        CoreCollectionPort.Result result = core.reverse(repayment.coreTransactionId(), idempotencyKey, actorId, actorName);
        return unitOfWork.required(() -> store.lockAccount(repayment.loanAccountId(), () -> unwind(repaymentId, result, actorId, actorName)));
    }

    public InterestAdjustment adjustInterest(UUID accountId, BigDecimal amount, String reason, UUID actorId, String actorName) {
        if (reason == null || reason.isBlank()) {
            throw ValidationException.field("reason", "An interest adjustment needs a reason");
        }
        if (actorId == null) {
            throw ValidationException.field("actorId", "An authorising officer is required");
        }
        return unitOfWork.required(() -> store.lockAccount(accountId, () -> {
            LoanAccount account = locked(accountId);
            if (account.status() == LoanAccountStatus.CLOSED || account.status() == LoanAccountStatus.PENDING_ACTIVATION) {
                throw new ConflictException("Interest cannot be adjusted on this loan");
            }
            BigDecimal delta = ServicingCalculator.money(amount, account.scale(), rounding(account));
            ServicingCalculator.adjustedInterest(account.outstandingInterest(), delta, account.scale(), rounding(account));
            String event = delta.signum() > 0 ? "INTEREST_ACCRUAL" : "INTEREST_REVERSAL";
            JournalHeader journal = draft(account, event, Map.of("INTEREST", delta.abs()), "Interest adjustment", actorId);
            InterestAdjustment adjustment = new InterestAdjustment(
                    UUID.randomUUID(), accountId, delta, reason.trim(), journal.id(), "PENDING", actorId, null, Instant.now(clock));
            store.insertAdjustment(adjustment);
            store.insertEvent(event(accountId, "INTEREST_ADJUSTMENT_REQUESTED", json(Map.of("amount", delta.toPlainString(), "journalId", journal.id().toString())), actorId, Instant.now(clock)));
            return adjustment;
        }));
    }

    public JournalHeader approveJournal(UUID journalId, UUID approverId) {
        JournalHeader header = store.findJournal(journalId).orElseThrow(() -> new ResourceNotFoundException("Journal"));
        if (header.status() == JournalStatus.POSTED) {
            throw new ConflictException("A posted journal cannot be edited");
        }
        if (approverId == null || approverId.equals(header.requestedBy())) {
            throw new ConflictException("A different officer must approve this journal");
        }
        return unitOfWork.required(() -> store.lockAccount(header.loanAccountId(), () -> {
            JournalHeader current = store.findJournal(journalId).orElseThrow();
            if (current.status() == JournalStatus.POSTED) {
                throw new ConflictException("A posted journal cannot be edited");
            }
            InterestAdjustment adjustment = store.findAdjustmentByJournal(journalId).orElseThrow(() -> new ConflictException("This journal is not awaiting approval"));
            if (!"PENDING".equals(adjustment.status())) {
                throw new ConflictException("This adjustment has already been decided");
            }
            LoanAccount account = locked(header.loanAccountId());
            BigDecimal outstanding = ServicingCalculator.adjustedInterest(account.outstandingInterest(), adjustment.amount(), account.scale(), rounding(account));
            BigDecimal accrued = account.accruedInterest().add(adjustment.amount()).setScale(account.scale(), rounding(account));
            if (accrued.compareTo(outstanding) < 0) {
                throw new ConflictException("The adjustment would take lifetime interest below the interest already outstanding");
            }
            store.postJournal(journalId, approverId, Instant.now(clock));
            LoanAccount updated = advance(account, account.position(), account.status(), account.outstandingPrincipal(), accrued, outstanding,
                    account.outstandingFees(), account.outstandingPenalties(), account.overpayment(), account.maturityDate(),
                    account.accrualStart(), account.lastAccruedThrough(), account.currentScheduleId(), account.scheduleVersion(),
                    account.interestRate(), account.closedAt());
            store.updateAccount(updated, account.version());
            snapshot(updated, today(), "INTEREST_ADJUSTED");
            store.updateAdjustment(new InterestAdjustment(
                    adjustment.id(), adjustment.loanAccountId(), adjustment.amount(), adjustment.reason(), adjustment.journalId(),
                    "POSTED", adjustment.requestedBy(), approverId, adjustment.createdAt()));
            store.insertEvent(event(account.id(), "INTEREST_ADJUSTED", json(Map.of("amount", adjustment.amount().toPlainString())), approverId, Instant.now(clock)));
            return store.findJournal(journalId).orElseThrow();
        }));
    }

    public LoanAccount restructure(RestructureCommand command) {
        if (command.reason() == null || command.reason().isBlank()) {
            throw ValidationException.field("reason", "A restructure needs a reason");
        }
        if (command.tenure() < 1) {
            throw ValidationException.field("tenure", "Tenure must be at least 1");
        }
        return unitOfWork.required(() -> store.lockAccount(command.loanAccountId(), () -> {
            LoanAccount account = locked(command.loanAccountId());
            LocalDate asOf = valueDate(account, command.valueDate());
            account = recognise(account, asOf, command.actorId(), command.actorName());
            if (account.status() == LoanAccountStatus.CLOSED) {
                throw new ConflictException("A closed loan cannot be restructured");
            }
            BigDecimal rate = command.interestRate() == null ? account.interestRate() : command.interestRate();
            BigDecimal principal = account.outstandingPrincipal();
            if (command.capitalizeInterest() && account.outstandingInterest().signum() > 0) {
                post(account, "CAPITALISE_INTEREST", Map.of("PRINCIPAL", account.outstandingInterest(), "INTEREST", account.outstandingInterest()),
                        "Capitalised interest", command.actorId(), null);
                principal = principal.add(account.outstandingInterest());
                account = advance(account, account.position(), account.status(), principal, account.accruedInterest(), zero(account.scale()),
                        account.outstandingFees(), account.outstandingPenalties(), account.overpayment(), account.maturityDate(),
                        account.accrualStart(), asOf, account.currentScheduleId(), account.scheduleVersion(), account.interestRate(), null);
                store.updateAccount(account, account.version() - 1);
            }
            LoanBooking booking = terms(account, principal, rate, command.tenure());
            HolidayRule holiday = command.holidayRule() == null ? HolidayRule.valueOf(account.holidayRule()) : command.holidayRule();
            ScheduleQuote quote = ServicingCalculator.schedule(request(booking, asOf, 0, command.tenure(), rate, command.repaymentDayOfMonth(), holiday));
            Instant now = Instant.now(clock);
            UUID scheduleId = UUID.randomUUID();
            store.supersedeCurrent(account.id());
            int version = account.scheduleVersion() + 1;
            LocalDate maturity = quote.rows().get(quote.rows().size() - 1).dueDate();
            store.insertSchedule(new ScheduleVersion(
                    scheduleId, account.id(), version, "CURRENT", account.currentScheduleId(), quote.quote().engineVersion(),
                    ServicingCalculator.VERSION, fingerprint(quote), parameters(booking, asOf, AccrualFrequency.valueOf(account.accrualFrequency()), quote),
                    command.reason().trim(), now, command.actorName()));
            store.insertLines(lines(scheduleId, quote, account.scale()));
            LoanAccount next = advance(account, account.position(), LoanAccountStatus.ACTIVE, principal, account.accruedInterest(),
                    account.outstandingInterest(), account.outstandingFees(), account.outstandingPenalties(), account.overpayment(),
                    maturity, asOf, asOf, scheduleId, version, rate, null);
            store.updateAccount(next, account.version());
            snapshot(next, asOf, "RESTRUCTURED");
            store.insertEvent(event(account.id(), "SCHEDULE_RESTRUCTURED", json(Map.of("version", version, "reason", command.reason().trim())), command.actorId(), now));
            audit("LOAN_SCHEDULE_RESTRUCTURED", account.id(), command.actorName(), Map.of("version", version));
            return next;
        }));
    }

    public Reconciliation reconcile(UUID loanAccountId) {
        if (loanAccountId == null) {
            BigDecimal servicing = store.postedCash(null);
            BigDecimal coreCash = core.confirmedServicingCash(null);
            return new Reconciliation(null, "NGN", servicing, coreCash, servicing.subtract(coreCash), servicing.compareTo(coreCash) == 0);
        }
        LoanAccount account = get(loanAccountId);
        BigDecimal servicing = store.postedCash(account.id());
        BigDecimal coreCash = core.confirmedServicingCash(account.applicationId());
        return new Reconciliation(account.id(), account.currencyCode(), servicing, coreCash, servicing.subtract(coreCash), servicing.compareTo(coreCash) == 0);
    }

    public LoanRepayment repayment(UUID id) {
        return store.findRepayment(id).orElseThrow(() -> new ResourceNotFoundException("Repayment"));
    }

    public List<RepaymentAllocation> allocations(UUID repaymentId) {
        repayment(repaymentId);
        return store.allocations(repaymentId);
    }

    public List<JournalHeader> journals(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return store.listJournals(safeSize, safePage * safeSize);
    }

    public long journalCount() {
        return store.countJournals();
    }

    public JournalHeader journal(UUID id) {
        return store.findJournal(id).orElseThrow(() -> new ResourceNotFoundException("Journal"));
    }

    public List<JournalLine> journalLines(UUID id) {
        journal(id);
        return store.journalLines(id);
    }

    private LoanRepayment receive(RepaymentCommand command) {
        try {
            return unitOfWork.required(() -> store.lockAccount(command.loanAccountId(), () -> prepare(command)));
        } catch (ConflictException ex) {
            return store.findRepaymentByKey(command.idempotencyKey()).orElseThrow(() -> ex);
        }
    }

    private LoanRepayment prepare(RepaymentCommand command) {
        var raced = store.findRepaymentByKey(command.idempotencyKey());
        if (raced.isPresent()) {
            return raced.get();
        }
        if (command.channel() == null) {
            throw ValidationException.field("channel", "A repayment channel is required");
        }
        LoanAccount account = recognise(locked(command.loanAccountId()), valueDate(locked(command.loanAccountId()), command.valueDate()), command.actorId(), command.actorName());
        if (account.status() == LoanAccountStatus.PENDING_ACTIVATION) {
            throw new ConflictException("The loan account is not active");
        }
        if (account.status() == LoanAccountStatus.CLOSED) {
            throw new ConflictException("The loan is closed");
        }
        LocalDate asOf = valueDate(account, command.valueDate());
        BigDecimal amount = ServicingCalculator.money(command.amount(), account.scale(), rounding(account));
        if (amount.signum() <= 0) {
            throw ValidationException.field("amount", "amount must be greater than zero");
        }
        if (command.settlement()) {
            SettlementQuote quote = quotation(account, store.lines(account.currentScheduleId()), asOf);
            if (amount.compareTo(quote.totalDue()) != 0) {
                throw ValidationException.field("amount", "Settlement amount must equal the quotation");
            }
        }
        Instant now = Instant.now(clock);
        LoanRepayment repayment = new LoanRepayment(
                UUID.randomUUID(), store.nextRepaymentReference(), account.id(), command.idempotencyKey(), command.channel(),
                RepaymentStatus.RECEIVED, amount, asOf, command.settlement(), zero(account.scale()), null, null,
                command.externalReference(), null, null, store.policyAt(asOf).versionNumber(), 0, null, now, now,
                command.actorId(), command.actorName());
        store.insertRepayment(repayment);
        history(repayment, RepaymentStatus.RECEIVED, "Received");
        return repayment;
    }

    private LoanRepayment progress(LoanRepayment repayment, UUID actorId, String actorName) {
        if (repayment.status() == RepaymentStatus.POSTED || repayment.status() == RepaymentStatus.FAILED || repayment.status() == RepaymentStatus.REVERSED) {
            return repayment;
        }
        LoanAccount account = get(repayment.loanAccountId());
        CoreCollectionPort.Result result = core.collect(account.applicationId(), repayment.idempotencyKey(), repayment.amount(), actorId, actorName);
        return unitOfWork.required(() -> store.lockAccount(account.id(), () -> apply(repayment.id(), result, actorId, actorName)));
    }

    private LoanRepayment apply(UUID repaymentId, CoreCollectionPort.Result result, UUID actorId, String actorName) {
        LoanRepayment repayment = store.findRepayment(repaymentId).orElseThrow();
        if (repayment.status() == RepaymentStatus.POSTED || repayment.status() == RepaymentStatus.FAILED || repayment.status() == RepaymentStatus.REVERSED) {
            return repayment;
        }
        if (!result.posted()) {
            RepaymentStatus status = result.failed() ? RepaymentStatus.FAILED : RepaymentStatus.AWAITING_CONFIRMATION;
            LoanRepayment updated = copy(repayment, status, result.failed() ? "Core banking did not confirm the repayment" : null, null, null, repayment.feeCharged(), result.transactionId(), result.reference());
            store.updateRepayment(updated, repayment.version());
            history(repayment, status, result.state());
            return updated;
        }
        LoanAccount account = recognise(locked(repayment.loanAccountId()), repayment.valueDate(), actorId, actorName);
        List<ScheduleLine> schedule = new ArrayList<>(store.lines(account.currentScheduleId()));
        SettlementQuote quote = quotation(account, schedule, repayment.valueDate(), false);
        BigDecimal feeCharged = repayment.feeCharged();
        UUID feeJournalId = repayment.feeJournalId();
        int repaymentVersion = repayment.version();
        if (quote.earlyFee().signum() > 0 && repayment.amount().compareTo(quote.totalDue()) >= 0 && repayment.valueDate().isBefore(account.maturityDate())) {
            JournalHeader feeJournal = post(account, "FEE_CHARGE", Map.of("FEE", quote.earlyFee()), "Early settlement fee", actorId, repayment.id());
            account = advance(account, account.position(), account.status(), account.outstandingPrincipal(), account.accruedInterest(),
                    account.outstandingInterest(), account.outstandingFees().add(quote.earlyFee()), account.outstandingPenalties(),
                    account.overpayment(), account.maturityDate(), account.accrualStart(), account.lastAccruedThrough(),
                    account.currentScheduleId(), account.scheduleVersion(), account.interestRate(), null);
            store.updateAccount(account, account.version() - 1);
            store.insertCharge(new LoanCharge(UUID.randomUUID(), account.id(), "EARLY_SETTLEMENT", quote.earlyFee(), feeJournal.id(), "Early settlement fee", Instant.now(clock)));
            feeCharged = quote.earlyFee();
            feeJournalId = feeJournal.id();
        }
        AllocationPolicy policy = store.policyAt(repayment.valueDate());
        Map<AllocationComponent, BigDecimal> buckets = new EnumMap<>(AllocationComponent.class);
        buckets.put(AllocationComponent.PENALTY, account.outstandingPenalties());
        buckets.put(AllocationComponent.FEE, account.outstandingFees());
        buckets.put(AllocationComponent.INTEREST, account.outstandingInterest());
        BigDecimal principalRoom = repayment.settlement() || !"NOT_ALLOWED".equals(account.earlyRepaymentRule())
                ? account.outstandingPrincipal()
                : duePrincipal(schedule, repayment.valueDate(), account);
        buckets.put(AllocationComponent.PRINCIPAL, principalRoom);
        AllocationResult allocation = ServicingCalculator.allocate(policy.priority(), buckets, repayment.amount(), account.scale(), rounding(account));
        BigDecimal principal = component(allocation, AllocationComponent.PRINCIPAL);
        BigDecimal interest = component(allocation, AllocationComponent.INTEREST);
        BigDecimal fees = component(allocation, AllocationComponent.FEE);
        BigDecimal penalties = component(allocation, AllocationComponent.PENALTY);
        Map<UUID, BigDecimal> principalBefore = new LinkedHashMap<>();
        Map<UUID, BigDecimal> interestBefore = new LinkedHashMap<>();
        for (ScheduleLine line : schedule) {
            principalBefore.put(line.id(), line.principalPaid());
            interestBefore.put(line.id(), line.interestPaid());
        }
        distribute(schedule, principal, interest, repayment.valueDate(), account);
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        amounts.put("CASH", repayment.amount());
        amounts.put("PRINCIPAL", principal);
        amounts.put("INTEREST", interest);
        amounts.put("FEE", fees);
        amounts.put("PENALTY", penalties);
        amounts.put("OVERPAYMENT", allocation.unapplied());
        JournalHeader cash = post(account, "REPAYMENT", amounts, "Repayment " + repayment.reference(), actorId, repayment.id());
        recordAllocations(repayment, schedule, principalBefore, interestBefore, principal, interest, fees, penalties, allocation.unapplied(), account.currencyCode());
        BigDecimal outstandingPrincipal = account.outstandingPrincipal().subtract(principal);
        BigDecimal outstandingInterest = account.outstandingInterest().subtract(interest);
        BigDecimal outstandingFees = account.outstandingFees().subtract(fees);
        BigDecimal outstandingPenalties = account.outstandingPenalties().subtract(penalties);
        BigDecimal overpayment = account.overpayment().add(allocation.unapplied());
        LoanAccountStatus status = live(outstandingPrincipal, outstandingInterest, outstandingFees, outstandingPenalties, schedule, repayment.valueDate());
        if (status == LoanAccountStatus.CLOSED) {
            waive(schedule, repayment.valueDate());
        }
        for (ScheduleLine line : schedule) {
            store.updateLine(line);
        }
        Instant closedAt = status == LoanAccountStatus.CLOSED ? Instant.now(clock) : null;
        LoanAccount next = advance(account, account.position(), status, outstandingPrincipal, account.accruedInterest(), outstandingInterest,
                outstandingFees, outstandingPenalties, overpayment, account.maturityDate(), account.accrualStart(), repayment.valueDate(),
                account.currentScheduleId(), account.scheduleVersion(), account.interestRate(), closedAt);
        store.updateAccount(next, account.version());
        snapshot(next, repayment.valueDate(), "REPAYMENT_POSTED");
        LoanRepayment posted = new LoanRepayment(
                repayment.id(), repayment.reference(), repayment.loanAccountId(), repayment.idempotencyKey(), repayment.channel(),
                RepaymentStatus.POSTED, repayment.amount(), repayment.valueDate(), repayment.settlement(), feeCharged,
                result.transactionId(), result.reference(), repayment.externalReference(), cash.id(), feeJournalId,
                policy.versionNumber(), repaymentVersion + 1, null, repayment.createdAt(), Instant.now(clock), repayment.actorId(), repayment.actorName());
        store.updateRepayment(posted, repaymentVersion);
        history(repayment, RepaymentStatus.POSTED, "Core banking confirmed the repayment");
        store.insertEvent(event(account.id(), "REPAYMENT_POSTED", json(Map.of("repaymentId", repayment.id().toString(), "amount", repayment.amount().toPlainString())), actorId, Instant.now(clock)));
        if (status == LoanAccountStatus.CLOSED) {
            store.insertEvent(event(account.id(), "CLOSED", "{\"reason\":\"Repayment cleared the loan\"}", actorId, Instant.now(clock)));
        }
        audit("LOAN_REPAYMENT_POSTED", repayment.id(), actorName, Map.of("reference", repayment.reference(), "state", "POSTED"));
        return posted;
    }

    private LoanRepayment unwind(UUID repaymentId, CoreCollectionPort.Result result, UUID actorId, String actorName) {
        LoanRepayment repayment = store.findRepayment(repaymentId).orElseThrow();
        if (repayment.status() == RepaymentStatus.REVERSED) {
            return repayment;
        }
        if (!result.reversed()) {
            if (result.failed() && repayment.status() == RepaymentStatus.REVERSAL_PENDING) {
                LoanRepayment restored = copy(repayment, RepaymentStatus.POSTED, "Core banking did not confirm the reversal", repayment.cashJournalId(), repayment.feeJournalId(), repayment.feeCharged(), repayment.coreTransactionId(), repayment.coreReference());
                store.updateRepayment(restored, repayment.version());
                history(repayment, RepaymentStatus.POSTED, "Reversal was not confirmed");
                return restored;
            }
            return repayment;
        }
        LoanAccount account = locked(repayment.loanAccountId());
        List<RepaymentAllocation> allocations = store.allocations(repayment.id());
        BigDecimal principal = sum(allocations, "PRINCIPAL");
        BigDecimal interest = sum(allocations, "INTEREST");
        BigDecimal fees = sum(allocations, "FEE");
        BigDecimal penalties = sum(allocations, "PENALTY");
        BigDecimal overpayment = sum(allocations, "OVERPAYMENT");
        List<ScheduleLine> schedule = new ArrayList<>(store.lines(account.currentScheduleId()));
        for (RepaymentAllocation allocation : allocations) {
            if (allocation.scheduleLineId() == null) {
                continue;
            }
            for (int index = 0; index < schedule.size(); index++) {
                ScheduleLine line = schedule.get(index);
                if (!line.id().equals(allocation.scheduleLineId())) {
                    continue;
                }
                BigDecimal principalPaid = "PRINCIPAL".equals(allocation.component()) ? line.principalPaid().subtract(allocation.amount()) : line.principalPaid();
                BigDecimal interestPaid = "INTEREST".equals(allocation.component()) ? line.interestPaid().subtract(allocation.amount()) : line.interestPaid();
                schedule.set(index, resized(line, principalPaid, interestPaid, line.penaltyAccrued(), lineStatus(line, principalPaid, interestPaid, repayment.valueDate(), false)));
            }
        }
        for (int index = 0; index < schedule.size(); index++) {
            ScheduleLine line = schedule.get(index);
            if ("WAIVED".equals(line.status())) {
                schedule.set(index, resized(line, line.principalPaid(), line.interestPaid(), line.penaltyAccrued(), lineStatus(line, line.principalPaid(), line.interestPaid(), repayment.valueDate(), false)));
            }
            store.updateLine(schedule.get(index));
        }
        BigDecimal outstandingFees = account.outstandingFees().add(fees).subtract(repayment.feeCharged());
        if (outstandingFees.signum() < 0) {
            outstandingFees = zero(account.scale());
        }
        reverseJournal(repayment.feeJournalId(), account, "Reversal of early settlement fee", actorId, repayment.id());
        reverseJournal(repayment.cashJournalId(), account, "Reversal of repayment " + repayment.reference(), actorId, repayment.id());
        BigDecimal outstandingPrincipal = account.outstandingPrincipal().add(principal);
        BigDecimal outstandingInterest = account.outstandingInterest().add(interest);
        BigDecimal outstandingPenalties = account.outstandingPenalties().add(penalties);
        BigDecimal remainingOverpayment = account.overpayment().subtract(overpayment);
        LoanAccountStatus status = live(outstandingPrincipal, outstandingInterest, outstandingFees, outstandingPenalties, schedule, repayment.valueDate());
        LoanAccount next = advance(account, account.position(), status, outstandingPrincipal, account.accruedInterest(), outstandingInterest,
                outstandingFees, outstandingPenalties, remainingOverpayment, account.maturityDate(), account.accrualStart(),
                account.lastAccruedThrough(), account.currentScheduleId(), account.scheduleVersion(), account.interestRate(), null);
        store.updateAccount(next, account.version());
        snapshot(next, repayment.valueDate(), "REPAYMENT_REVERSED");
        LoanRepayment reversed = copy(repayment, RepaymentStatus.REVERSED, null, repayment.cashJournalId(), repayment.feeJournalId(), repayment.feeCharged(), repayment.coreTransactionId(), repayment.coreReference());
        store.updateRepayment(reversed, repayment.version());
        history(repayment, RepaymentStatus.REVERSED, "Core banking confirmed the reversal");
        store.insertEvent(event(account.id(), "REPAYMENT_REVERSED", json(Map.of("repaymentId", repayment.id().toString())), actorId, Instant.now(clock)));
        audit("LOAN_REPAYMENT_REVERSED", repayment.id(), actorName, Map.of("reference", repayment.reference()));
        return reversed;
    }

    private LoanAccount recognise(LoanAccount account, LocalDate asOf, UUID actorId, String actorName) {
        if (account.status() == LoanAccountStatus.PENDING_ACTIVATION || account.status() == LoanAccountStatus.CLOSED) {
            return account;
        }
        List<ScheduleLine> schedule = new ArrayList<>(store.lines(account.currentScheduleId()));
        List<DailySlice> slices = slices(account, schedule);
        if (asOf.isAfter(account.lastAccruedThrough())) {
            List<RecognisedInterest> recognised = ServicingCalculator.recognise(slices, account.lastAccruedThrough(), asOf, AccrualFrequency.valueOf(account.accrualFrequency()));
            BigDecimal interest = recognised.stream().map(RecognisedInterest::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            UUID journalId = null;
            if (interest.signum() > 0) {
                journalId = post(account, "INTEREST_ACCRUAL", Map.of("INTEREST", interest), "Interest accrual", actorId, null).id();
            }
            Instant now = Instant.now(clock);
            for (RecognisedInterest row : recognised) {
                if (row.amount().signum() == 0) {
                    continue;
                }
                store.insertAccrual(new InterestAccrual(UUID.randomUUID(), account.id(), row.date(), account.accrualFrequency(), "INTEREST", row.amount(), account.dayCountConvention(), journalId, now));
            }
            if (interest.signum() > 0) {
                account = advance(account, account.position(), account.status(), account.outstandingPrincipal(), account.accruedInterest().add(interest),
                        account.outstandingInterest().add(interest), account.outstandingFees(), account.outstandingPenalties(), account.overpayment(),
                        account.maturityDate(), account.accrualStart(), asOf, account.currentScheduleId(), account.scheduleVersion(), account.interestRate(), null);
                store.updateAccount(account, account.version() - 1);
                snapshot(account, asOf, "INTEREST_ACCRUED");
            } else {
                account = advance(account, account.position(), account.status(), account.outstandingPrincipal(), account.accruedInterest(),
                        account.outstandingInterest(), account.outstandingFees(), account.outstandingPenalties(), account.overpayment(),
                        account.maturityDate(), account.accrualStart(), asOf, account.currentScheduleId(), account.scheduleVersion(), account.interestRate(), account.closedAt());
                store.updateAccount(account, account.version() - 1);
            }
        }
        BigDecimal penalty = zero(account.scale());
        for (int index = 0; index < schedule.size(); index++) {
            ScheduleLine line = schedule.get(index);
            boolean open = line.principalPaid().compareTo(line.principalDue()) < 0 || line.interestPaid().compareTo(line.interestDue()) < 0;
            BigDecimal total = ServicingCalculator.penaltyTotal(new ServicingCalculator.PenaltyRequest(
                    PenaltyBasis.valueOf(account.penaltyBasis()),
                    line.principalDue().subtract(line.principalPaid()).add(line.interestDue().subtract(line.interestPaid())),
                    line.instalmentAmount(),
                    account.penaltyAmount(),
                    account.penaltyRate(),
                    line.dueDate(),
                    account.penaltyGraceDays(),
                    asOf,
                    DayCountConvention.valueOf(account.dayCountConvention()),
                    account.scale(),
                    rounding(account),
                    open && !"WAIVED".equals(line.status())));
            BigDecimal delta = total.subtract(line.penaltyAccrued());
            if (delta.signum() > 0) {
                penalty = penalty.add(delta);
                schedule.set(index, resized(line, line.principalPaid(), line.interestPaid(), total, line.status()));
                store.updateLine(schedule.get(index));
            }
        }
        if (penalty.signum() > 0) {
            JournalHeader journal = post(account, "PENALTY_ACCRUAL", Map.of("PENALTY", penalty), "Late payment penalty", actorId, null);
            store.insertAccrual(new InterestAccrual(UUID.randomUUID(), account.id(), asOf, account.accrualFrequency(), "PENALTY", penalty, account.dayCountConvention(), journal.id(), Instant.now(clock)));
            account = advance(account, account.position(), account.status(), account.outstandingPrincipal(), account.accruedInterest(),
                    account.outstandingInterest(), account.outstandingFees(), account.outstandingPenalties().add(penalty), account.overpayment(),
                    account.maturityDate(), account.accrualStart(), account.lastAccruedThrough(), account.currentScheduleId(), account.scheduleVersion(),
                    account.interestRate(), null);
            store.updateAccount(account, account.version() - 1);
            snapshot(account, asOf, "PENALTY_ACCRUED");
        }
        LoanAccountStatus status = live(account.outstandingPrincipal(), account.outstandingInterest(), account.outstandingFees(), account.outstandingPenalties(), schedule, asOf);
        if (status != account.status() && account.status() != LoanAccountStatus.CLOSED) {
            account = advance(account, account.position(), status, account.outstandingPrincipal(), account.accruedInterest(), account.outstandingInterest(),
                    account.outstandingFees(), account.outstandingPenalties(), account.overpayment(), account.maturityDate(), account.accrualStart(),
                    account.lastAccruedThrough(), account.currentScheduleId(), account.scheduleVersion(), account.interestRate(), null);
            store.updateAccount(account, account.version() - 1);
            store.insertEvent(event(account.id(), "STATUS_CHANGED", json(Map.of("status", status.name())), actorId, Instant.now(clock)));
        }
        return account;
    }

    private SettlementQuote quotation(LoanAccount account, List<ScheduleLine> lines, LocalDate asOf) {
        return quotation(account, lines, asOf, true);
    }

    private SettlementQuote quotation(LoanAccount account, List<ScheduleLine> lines, LocalDate asOf, boolean enforceEarlySettlementRule) {
        BigDecimal extraPenalty = zero(account.scale());
        for (ScheduleLine line : lines) {
            boolean open = line.principalPaid().compareTo(line.principalDue()) < 0 || line.interestPaid().compareTo(line.interestDue()) < 0;
            BigDecimal total = ServicingCalculator.penaltyTotal(new ServicingCalculator.PenaltyRequest(
                    PenaltyBasis.valueOf(account.penaltyBasis()),
                    line.principalDue().subtract(line.principalPaid()).add(line.interestDue().subtract(line.interestPaid())),
                    line.instalmentAmount(),
                    account.penaltyAmount(),
                    account.penaltyRate(),
                    line.dueDate(),
                    account.penaltyGraceDays(),
                    asOf,
                    DayCountConvention.valueOf(account.dayCountConvention()),
                    account.scale(),
                    rounding(account),
                    open && !"WAIVED".equals(line.status())));
            if (total.compareTo(line.penaltyAccrued()) > 0) {
                extraPenalty = extraPenalty.add(total.subtract(line.penaltyAccrued()));
            }
        }
        BigDecimal contractual = lines.stream().map(ScheduleLine::interestDue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal interestPaid = account.accruedInterest().subtract(account.outstandingInterest());
        return ServicingCalculator.settlement(new ServicingCalculator.SettlementInput(
                account.outstandingPrincipal(),
                interestPaid,
                contractual,
                account.outstandingFees(),
                account.outstandingPenalties().add(extraPenalty),
                slices(account, lines),
                account.disbursementDate(),
                account.maturityDate(),
                asOf,
                EarlyRepaymentRule.valueOf(account.earlyRepaymentRule()),
                account.earlyRepaymentFeeRate(),
                account.scale(),
                rounding(account),
                enforceEarlySettlementRule));
    }

    private JournalHeader post(LoanAccount account, String eventCode, Map<String, BigDecimal> amounts, String narration, UUID actorId, UUID sourceId) {
        Draft draft = AccountingEngine.draft(eventCode, account.currencyCode(), amounts, store.postingRules(), account.scale(), rounding(account));
        return storeJournal(account, draft, narration, actorId, sourceId, false, JournalStatus.POSTED, null, null);
    }

    private JournalHeader draft(LoanAccount account, String eventCode, Map<String, BigDecimal> amounts, String narration, UUID actorId) {
        Draft draft = AccountingEngine.draft(eventCode, account.currencyCode(), amounts, store.postingRules(), account.scale(), rounding(account));
        return storeJournal(account, new Draft(draft.eventCode(), draft.currencyCode(), true, draft.lines()), narration, actorId, null, true, JournalStatus.DRAFT, actorId, null);
    }

    private JournalHeader storeJournal(
            LoanAccount account,
            Draft draft,
            String narration,
            UUID actorId,
            UUID sourceId,
            boolean approval,
            JournalStatus status,
            UUID requestedBy,
            UUID reversalOf) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        JournalHeader header = new JournalHeader(
                id, store.nextJournalReference(), account.id(), draft.eventCode(), account.currencyCode(), status, reversalOf,
                approval, requestedBy, status == JournalStatus.POSTED ? actorId : null, status == JournalStatus.POSTED ? now : null, now);
        List<JournalLine> lines = new ArrayList<>();
        for (DraftLine line : draft.lines()) {
            lines.add(new JournalLine(UUID.randomUUID(), id, line.number(), line.accountCode(), line.direction(), line.amount(), line.currencyCode(), narration));
        }
        store.insertJournal(header, lines);
        store.insertAccountingEvent(new AccountingEvent(UUID.randomUUID(), account.id(), draft.eventCode(), sourceId, id, narration, now));
        return header;
    }

    private void reverseJournal(UUID journalId, LoanAccount account, String reason, UUID actorId, UUID repaymentId) {
        if (journalId == null) {
            return;
        }
        JournalHeader original = store.findJournal(journalId).orElseThrow();
        if (original.status() != JournalStatus.POSTED) {
            throw new ConflictException("Only a posted journal can be reversed");
        }
        List<DraftLine> lines = store.journalLines(journalId).stream()
                .map(line -> new DraftLine(line.lineNumber(), line.accountCode(), line.direction(), line.amount(), line.currencyCode(), line.narration()))
                .toList();
        Draft reversal = AccountingEngine.reverse(account.currencyCode(), lines);
        JournalHeader header = storeJournal(account, reversal, reason, actorId, repaymentId, false, JournalStatus.POSTED, null, journalId);
        store.insertReversal(new TransactionReversal(UUID.randomUUID(), repaymentId, journalId, header.id(), reason, Instant.now(clock)));
    }

    private void recordAllocations(
            LoanRepayment repayment,
            List<ScheduleLine> schedule,
            Map<UUID, BigDecimal> principalBefore,
            Map<UUID, BigDecimal> interestBefore,
            BigDecimal principal,
            BigDecimal interest,
            BigDecimal fees,
            BigDecimal penalties,
            BigDecimal overpayment,
            String currency) {
        bucket(repayment.id(), "PENALTY", penalties, null, currency);
        bucket(repayment.id(), "FEE", fees, null, currency);
        bucket(repayment.id(), "INTEREST", interest, null, currency);
        bucket(repayment.id(), "PRINCIPAL", principal, null, currency);
        bucket(repayment.id(), "OVERPAYMENT", overpayment, null, currency);
        for (ScheduleLine line : schedule) {
            bucket(repayment.id(), "PRINCIPAL", line.principalPaid().subtract(principalBefore.getOrDefault(line.id(), BigDecimal.ZERO)), line.id(), currency);
            bucket(repayment.id(), "INTEREST", line.interestPaid().subtract(interestBefore.getOrDefault(line.id(), BigDecimal.ZERO)), line.id(), currency);
        }
    }

    private void bucket(UUID repaymentId, String component, BigDecimal amount, UUID scheduleLineId, String currency) {
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        store.insertAllocation(new RepaymentAllocation(UUID.randomUUID(), repaymentId, component, amount, scheduleLineId, currency));
    }

    private void distribute(List<ScheduleLine> schedule, BigDecimal principal, BigDecimal interest, LocalDate asOf, LoanAccount account) {
        List<DailySlice> slices = slices(account, schedule);
        BigDecimal principalLeft = principal;
        BigDecimal interestLeft = interest;
        for (int index = 0; index < schedule.size(); index++) {
            ScheduleLine line = schedule.get(index);
            BigDecimal principalRoom = line.principalDue().subtract(line.principalPaid());
            BigDecimal principalApplied = principalRoom.min(principalLeft).max(zero(account.scale()));
            BigDecimal earned = slices.stream()
                    .filter(slice -> slice.instalmentNumber() == line.number() && !slice.date().isAfter(asOf))
                    .map(DailySlice::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal interestRoom = earned.subtract(line.interestPaid());
            if (interestRoom.signum() < 0) {
                interestRoom = zero(account.scale());
            }
            BigDecimal interestApplied = interestRoom.min(interestLeft);
            principalLeft = principalLeft.subtract(principalApplied);
            interestLeft = interestLeft.subtract(interestApplied);
            BigDecimal principalPaid = line.principalPaid().add(principalApplied);
            BigDecimal interestPaid = line.interestPaid().add(interestApplied);
            schedule.set(index, resized(line, principalPaid, interestPaid, line.penaltyAccrued(), lineStatus(line, principalPaid, interestPaid, asOf, false)));
        }
        if (principalLeft.signum() > 0) {
            throw new IllegalStateException("Principal allocation exceeded the schedule");
        }
    }

    private List<DailySlice> slices(LoanAccount account, List<ScheduleLine> lines) {
        List<ScheduleRow> rows = lines.stream()
                .map(line -> new ScheduleRow(line.number(), line.contractualDueDate(), line.dueDate(), line.openingBalance(), line.principalDue(), line.interestDue(), line.instalmentAmount(), line.closingBalance()))
                .toList();
        return ServicingCalculator.slices(account.accrualStart(), rows, account.scale(), rounding(account));
    }

    private BigDecimal duePrincipal(List<ScheduleLine> lines, LocalDate asOf, LoanAccount account) {
        Map<Integer, BigDecimal> paid = new LinkedHashMap<>();
        List<ScheduleRow> rows = new ArrayList<>();
        for (ScheduleLine line : lines) {
            paid.put(line.number(), line.principalPaid());
            rows.add(new ScheduleRow(line.number(), line.contractualDueDate(), line.dueDate(), line.openingBalance(), line.principalDue(), line.interestDue(), line.instalmentAmount(), line.closingBalance()));
        }
        return ServicingCalculator.duePrincipal(rows, paid, asOf, account.scale(), rounding(account));
    }

    private void waive(List<ScheduleLine> schedule, LocalDate asOf) {
        for (int index = 0; index < schedule.size(); index++) {
            ScheduleLine line = schedule.get(index);
            if (!"PAID".equals(line.status())) {
                schedule.set(index, resized(line, line.principalPaid(), line.interestPaid(), line.penaltyAccrued(), "WAIVED"));
            }
        }
    }

    private static LoanAccountStatus live(BigDecimal principal, BigDecimal interest, BigDecimal fees, BigDecimal penalties, List<ScheduleLine> lines, LocalDate asOf) {
        if (principal.signum() == 0 && interest.signum() == 0 && fees.signum() == 0 && penalties.signum() == 0) {
            return LoanAccountStatus.CLOSED;
        }
        boolean overdue = lines.stream().anyMatch(line -> !line.dueDate().isAfter(asOf) && !"PAID".equals(line.status()) && !"WAIVED".equals(line.status()));
        return overdue ? LoanAccountStatus.OVERDUE : LoanAccountStatus.ACTIVE;
    }

    private static String lineStatus(ScheduleLine line, BigDecimal principalPaid, BigDecimal interestPaid, LocalDate asOf, boolean waived) {
        if (waived) {
            return "WAIVED";
        }
        boolean paid = principalPaid.compareTo(line.principalDue()) >= 0 && interestPaid.compareTo(line.interestDue()) >= 0;
        if (paid) {
            return "PAID";
        }
        boolean any = principalPaid.signum() > 0 || interestPaid.signum() > 0;
        if (!line.dueDate().isAfter(asOf)) {
            return any ? "PARTIAL" : "OVERDUE";
        }
        return any ? "PARTIAL" : "SCHEDULED";
    }

    private static ScheduleLine resized(ScheduleLine line, BigDecimal principalPaid, BigDecimal interestPaid, BigDecimal penalty, String status) {
        return new ScheduleLine(
                line.id(), line.scheduleId(), line.number(), line.contractualDueDate(), line.dueDate(), line.openingBalance(),
                line.principalDue(), line.interestDue(), line.instalmentAmount(), line.closingBalance(), principalPaid, interestPaid, penalty, status);
    }

    private LoanAccount advance(
            LoanAccount account,
            Position ignored,
            LoanAccountStatus status,
            BigDecimal principal,
            BigDecimal accrued,
            BigDecimal interest,
            BigDecimal fees,
            BigDecimal penalties,
            BigDecimal overpayment,
            LocalDate maturity,
            LocalDate accrualStart,
            LocalDate lastAccrued,
            UUID scheduleId,
            int scheduleVersion,
            BigDecimal rate,
            Instant closedAt) {
        return account.withPosition(new Position(
                status, principal, accrued, interest, fees, penalties, overpayment, maturity, accrualStart, lastAccrued,
                scheduleId, scheduleVersion, rate, closedAt, account.version() + 1, Instant.now(clock)));
    }

    private void snapshot(LoanAccount account, LocalDate valueDate, String reason) {
        store.insertSnapshot(new BalanceSnapshot(
                UUID.randomUUID(), account.id(), valueDate, account.outstandingPrincipal(), account.accruedInterest(),
                account.outstandingInterest(), account.outstandingFees(), account.outstandingPenalties(), account.overpayment(),
                reason, Instant.now(clock)));
    }

    private AccountEvent event(UUID accountId, String type, String payload, UUID actorId, Instant at) {
        return new AccountEvent(UUID.randomUUID(), accountId, type, payload, actorId, at);
    }

    private void history(LoanRepayment repayment, RepaymentStatus to, String reason) {
        store.insertHistory(new RepaymentHistory(UUID.randomUUID(), repayment.id(), repayment.status().name(), to.name(), reason, Instant.now(clock)));
    }

    private LoanRepayment copy(
            LoanRepayment repayment,
            RepaymentStatus status,
            String failure,
            UUID cashJournalId,
            UUID feeJournalId,
            BigDecimal feeCharged,
            UUID coreId,
            String coreReference) {
        return new LoanRepayment(
                repayment.id(), repayment.reference(), repayment.loanAccountId(), repayment.idempotencyKey(), repayment.channel(),
                status, repayment.amount(), repayment.valueDate(), repayment.settlement(), feeCharged, coreId, coreReference,
                repayment.externalReference(), cashJournalId, feeJournalId, repayment.allocationPolicyVersion(), repayment.version() + 1,
                failure, repayment.createdAt(), Instant.now(clock), repayment.actorId(), repayment.actorName());
    }

    private LoanAccount locked(UUID id) {
        return store.findAccount(id).orElseThrow(() -> new ResourceNotFoundException("Loan account"));
    }

    private LocalDate valueDate(LoanAccount account, LocalDate requested) {
        LocalDate date = requested == null ? today() : requested;
        if (date.isBefore(account.disbursementDate())) {
            throw ValidationException.field("valueDate", "Value date cannot be before disbursement");
        }
        if (date.isAfter(today())) {
            throw ValidationException.field("valueDate", "Value date cannot be in the future");
        }
        return date;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), BANK);
    }

    private static void validateKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw ValidationException.field("idempotencyKey", "idempotencyKey must be 8 to 64 letters, numbers, or . _ : -");
        }
    }

    private ScheduleRequest request(
            LoanBooking booking,
            LocalDate disbursementDate,
            int grace,
            int tenure,
            BigDecimal rate,
            Integer repaymentDay,
            HolidayRule holiday) {
        return new ScheduleRequest(
                booking.principal(), rate, booking.interestMethod(), booking.interestPeriod(), tenure, booking.tenureUnit(),
                booking.frequency(), booking.dayCount(), booking.scale(), booking.rounding(), grace, disbursementDate,
                booking.taxRate(), booking.fees(), repaymentDay, holiday, List.of());
    }

    private LoanBooking terms(LoanAccount account, BigDecimal principal, BigDecimal rate, int tenure) {
        return new LoanBooking(
                account.applicationId(), account.customerId(), account.branchId(), account.productId(), account.productVersionId(),
                account.offerId(), principal, rate, InterestMethod.valueOf(account.interestMethod()), InterestPeriod.valueOf(account.interestPeriod()),
                tenure, tenureUnit(account.repaymentFrequency()), RepaymentFrequency.valueOf(account.repaymentFrequency()),
                DayCountConvention.valueOf(account.dayCountConvention()), account.scale(), rounding(account), 0, BigDecimal.ZERO,
                List.of(), EarlyRepaymentRule.valueOf(account.earlyRepaymentRule()), account.earlyRepaymentFeeRate(),
                PenaltyBasis.valueOf(account.penaltyBasis()), account.penaltyRate(), account.penaltyAmount(), account.penaltyGraceDays(),
                true, account.coreDisbursementReference());
    }

    private static TenureUnit tenureUnit(String frequency) {
        return switch (RepaymentFrequency.valueOf(frequency)) {
            case MONTHLY -> TenureUnit.MONTHS;
            case WEEKLY -> TenureUnit.WEEKS;
            case DAILY -> TenureUnit.DAYS;
        };
    }

    private List<ScheduleLine> lines(UUID scheduleId, ScheduleQuote quote, int scale) {
        List<ScheduleLine> lines = new ArrayList<>();
        BigDecimal zero = zero(scale);
        for (ScheduleRow row : quote.rows()) {
            lines.add(new ScheduleLine(
                    UUID.randomUUID(), scheduleId, row.number(), row.contractualDueDate(), row.dueDate(), row.openingBalance(),
                    row.principalDue(), row.interestDue(), row.instalmentAmount(), row.closingBalance(), zero, zero, zero, "SCHEDULED"));
        }
        return lines;
    }

    private String parameters(LoanBooking booking, LocalDate disbursementDate, AccrualFrequency frequency, ScheduleQuote quote) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("engineVersion", quote.quote().engineVersion());
        body.put("quoteFingerprint", quote.quote().fingerprint());
        body.put("servicingVersion", ServicingCalculator.VERSION);
        body.put("principal", quote.quote().principal().toPlainString());
        body.put("interestRate", booking.interestRate().toPlainString());
        body.put("interestMethod", booking.interestMethod().name());
        body.put("interestPeriod", booking.interestPeriod().name());
        body.put("tenure", booking.tenure());
        body.put("tenureUnit", booking.tenureUnit().name());
        body.put("frequency", booking.frequency().name());
        body.put("dayCount", booking.dayCount().name());
        body.put("scale", booking.scale());
        body.put("rounding", booking.rounding().name());
        body.put("gracePeriodDays", booking.gracePeriodDays());
        body.put("disbursementDate", disbursementDate.toString());
        body.put("accrualFrequency", frequency.name());
        body.put("holidayRule", HolidayRule.NONE.name());
        return json(body);
    }

    private static String fingerprint(ScheduleQuote quote) {
        StringBuilder canonical = new StringBuilder(ServicingCalculator.VERSION)
                .append('|').append(quote.quote().fingerprint());
        for (ScheduleRow row : quote.rows()) {
            canonical.append("|row=").append(row.number()).append(':').append(row.contractualDueDate()).append(':').append(row.dueDate())
                    .append(':').append(row.principalDue().toPlainString()).append(':').append(row.interestDue().toPlainString());
        }
        return ServicingCalculator.fingerprint(canonical.toString());
    }

    private String json(Map<String, ?> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Servicing payload could not be written", ex);
        }
    }

    private void audit(String action, UUID id, String actor, Map<String, Object> after) {
        if (audit != null) {
            audit.record(action, "loan_account", id.toString(), actor, null, after);
        }
    }

    private static BigDecimal component(AllocationResult result, AllocationComponent component) {
        return result.slices().stream()
                .filter(slice -> slice.component() == component)
                .map(ServicingCalculator.AllocationSlice::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sum(List<RepaymentAllocation> allocations, String component) {
        return allocations.stream()
                .filter(row -> row.scheduleLineId() == null && component.equals(row.component()))
                .map(RepaymentAllocation::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static RoundingMode rounding(LoanAccount account) {
        return RoundingMode.valueOf(account.roundingMethod());
    }

    private static BigDecimal money(BigDecimal value, int scale, RoundingMode rounding) {
        return ServicingCalculator.money(value == null ? BigDecimal.ZERO : value, scale, rounding);
    }

    private static BigDecimal zero(int scale) {
        return ServicingCalculator.zero(scale);
    }

    public record Page(List<LoanAccount> items, int page, int size, long total) {
    }

    public record ScheduleView(LoanAccount account, ScheduleVersion schedule, List<ScheduleLine> lines) {
    }

    public record StatementView(
            LoanAccount account,
            LocalDate from,
            LocalDate to,
            BalanceSnapshot opening,
            List<BalanceSnapshot> movements,
            List<AccountEvent> events,
            List<LoanRepayment> repayments,
            BigDecimal contractualInterest,
            BigDecimal interestPaid,
            BigDecimal unearnedInterest
    ) {
    }

    public record Reconciliation(
            UUID loanAccountId,
            String currencyCode,
            BigDecimal servicingCash,
            BigDecimal coreServicingCash,
            BigDecimal difference,
            boolean matched
    ) {
    }
}
