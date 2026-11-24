package com.mfb.lending.module.reporting.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import com.mfb.lending.config.ReportingProperties;
import com.mfb.lending.module.reporting.calculation.ReportTables;
import com.mfb.lending.module.reporting.calculation.ReportingCalculator;
import com.mfb.lending.module.reporting.calculation.ReportingCalculator.Rate;
import com.mfb.lending.module.reporting.calculation.ReportingRules;
import com.mfb.lending.module.reporting.domain.GroupDimension;
import com.mfb.lending.module.reporting.domain.LedgerMovement;
import com.mfb.lending.module.reporting.domain.ReportCode;
import com.mfb.lending.module.reporting.domain.ReportingModels.ApplicationCounts;
import com.mfb.lending.module.reporting.domain.ReportingModels.BalanceMetrics;
import com.mfb.lending.module.reporting.domain.ReportingModels.CustomerApplication;
import com.mfb.lending.module.reporting.domain.ReportingModels.CustomerLoanPage;
import com.mfb.lending.module.reporting.domain.ReportingModels.CustomerPosition;
import com.mfb.lending.module.reporting.domain.ReportingModels.CustomerRepaymentPage;
import com.mfb.lending.module.reporting.domain.ReportingModels.DecisionMetrics;
import com.mfb.lending.module.reporting.domain.ReportingModels.DetailPage;
import com.mfb.lending.module.reporting.domain.ReportingModels.FlowMetrics;
import com.mfb.lending.module.reporting.domain.ReportingModels.StockMetrics;
import com.mfb.lending.module.reporting.domain.ReportingModels.WriteOffMetrics;
import com.mfb.lending.module.reporting.api.ReportQuery;
import com.mfb.lending.module.reporting.dto.ReportResponses.BranchDashboard;
import com.mfb.lending.module.reporting.dto.ReportResponses.CollectionsDashboard;
import com.mfb.lending.module.reporting.dto.ReportResponses.CreditDashboard;
import com.mfb.lending.module.reporting.dto.ReportResponses.CustomerDashboard;
import com.mfb.lending.module.reporting.dto.ReportResponses.DefaultReport;
import com.mfb.lending.module.reporting.dto.ReportResponses.DelinquencyReport;
import com.mfb.lending.module.reporting.dto.ReportResponses.GroupReport;
import com.mfb.lending.module.reporting.dto.ReportResponses.GroupRow;
import com.mfb.lending.module.reporting.dto.ReportResponses.IncomeReport;
import com.mfb.lending.module.reporting.dto.ReportResponses.MoneyReport;
import com.mfb.lending.module.reporting.dto.ReportResponses.OperationsDashboard;
import com.mfb.lending.module.reporting.dto.ReportResponses.ParSummary;
import com.mfb.lending.module.reporting.dto.ReportResponses.PortfolioReport;
import com.mfb.lending.module.reporting.dto.ReportResponses.ReconciliationReport;
import com.mfb.lending.module.reporting.dto.ReportResponses.Rules;
import com.mfb.lending.module.reporting.dto.ReportResponses.StatementSummary;
import com.mfb.lending.module.reporting.dto.ReportResponses.WriteOffReport;
import com.mfb.lending.module.reporting.persistence.ReportingStore;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import org.springframework.stereotype.Service;

@Service
public class ReportingService {

    private final ReportingStore store;
    private final ReportingAccess access;
    private final ReportingProperties properties;
    private final java.time.Clock clock;

    public ReportingService(ReportingStore store, ReportingAccess access, ReportingProperties properties, java.time.Clock clock) {
        this.store = store;
        this.access = access;
        this.properties = properties;
        this.clock = clock;
    }

    public CustomerDashboard customer(AuthenticatedUser actor) {
        java.util.UUID customerId = access.ownCustomer(actor);
        CustomerPosition position = store.customerPosition(customerId);
        List<CustomerApplication> applications = store.customerApplications(customerId, 100);
        return new CustomerDashboard("NGN", position.activeLoans(), kobo(position.outstandingPrincipal()), kobo(position.outstandingInterest()),
                kobo(position.outstandingFees()), kobo(position.outstandingPenalties()), position.nextRepaymentDate(),
                position.nextRepaymentAmount() == null ? null : kobo(position.nextRepaymentAmount()),
                new StatementSummary(kobo(position.principalPaid()), kobo(position.interestPaid()), kobo(position.feesPaid()),
                        kobo(position.penaltiesPaid()), position.lastPaymentDate()),
                applications, store.customerApplicationCount(customerId));
    }

    public com.mfb.lending.module.reporting.dto.ReportResponses.CustomerLoanPage customerLoans(AuthenticatedUser actor, ReportQuery query) {
        java.util.UUID customerId = access.ownCustomer(actor);
        PageRequest page = PageRequest.of(query.getPage(), query.getSize());
        CustomerLoanPage result = store.customerLoans(customerId, page.size(), page.offset(), page.page(), page.size());
        return new com.mfb.lending.module.reporting.dto.ReportResponses.CustomerLoanPage(result.loans(), page.page(), page.size(), result.total());
    }

    public com.mfb.lending.module.reporting.dto.ReportResponses.CustomerRepaymentPage customerRepayments(AuthenticatedUser actor, ReportQuery query) {
        java.util.UUID customerId = access.ownCustomer(actor);
        PageRequest page = PageRequest.of(query.getPage(), query.getSize());
        var window = ReportingScope.customerWindow(query.getStartDate(), query.getEndDate(), zone(), clock, properties.getMaxRangeDays());
        CustomerRepaymentPage result = store.customerRepayments(
                customerId, window.dueFrom(), window.dueToExclusive(), page.size(), page.offset(), page.page(), page.size());
        return new com.mfb.lending.module.reporting.dto.ReportResponses.CustomerRepaymentPage(
                result.repayments(), page.page(), page.size(), result.total(), window.bounded(), window.start(), window.end());
    }

    public OperationsDashboard operations(AuthenticatedUser actor, ReportQuery query) {
        access.viewOperations(actor);
        ReportingScope scope = scope(actor, query);
        ApplicationCounts applications = store.applications(scope);
        StockMetrics stock = store.stock(scope);
        FlowMetrics flow = store.flow(scope);
        Rate collection = ReportingCalculator.definedRate(flow.subledgerCollection(), flow.contractualDue());
        return new OperationsDashboard("NGN", scope.stockDate(), stock.stockBasis(), scope.bounded(), scope.startDate(), scope.endDate(),
                applications, flow.disbursementCount(), ledger(flow.subledgerDisbursement()), ledger(stock.outstandingPrincipal()),
                ledger(stock.outstandingInterest()), ledger(stock.overduePrincipal()), ledger(flow.subledgerCollection()),
                ledger(flow.contractualDue()), collection.value(), collection.defined());
    }

    public CreditDashboard credit(AuthenticatedUser actor, ReportQuery query) {
        access.viewCredit(actor);
        ReportingScope scope = scope(actor, query);
        ApplicationCounts applications = store.applications(scope);
        DecisionMetrics decisions = store.decisions(scope);
        FlowMetrics flow = store.flow(scope);
        Rate approval = ReportingCalculator.decisionRate(decisions.approved(), decisions.approved(), decisions.rejected());
        Rate rejection = ReportingCalculator.decisionRate(decisions.rejected(), decisions.approved(), decisions.rejected());
        return new CreditDashboard(scope.bounded(), scope.startDate(), scope.endDate(), applications, decisions.approved(), decisions.rejected(),
                approval.value(), approval.defined(), rejection.value(), rejection.defined(),
                ReportingCalculator.turnaroundDays(decisions.turnaroundDays()), decisions.turnaroundSample(),
                ReportingCalculator.averageLoan(flow.generalLedgerDisbursement(), flow.disbursementCount()),
                PortfolioGroups.composition(store, scope, GroupDimension.RISK), null, null);
    }

    public CollectionsDashboard collections(AuthenticatedUser actor, ReportQuery query) {
        access.viewCollections(actor);
        ReportingScope scope = scope(actor, query);
        StockMetrics stock = store.stock(scope);
        FlowMetrics flow = store.flow(scope);
        Rate collection = ReportingCalculator.definedRate(flow.subledgerCollection(), flow.contractualDue());
        return new CollectionsDashboard(scope.stockDate(), stock.stockBasis(), ledger(stock.outstandingPrincipal()), ledger(stock.overduePrincipal()),
                stock.overdueCount(), ratio(stock.par30Principal(), stock.outstandingPrincipal()), ratio(stock.par60Principal(), stock.outstandingPrincipal()),
                ratio(stock.par90Principal(), stock.outstandingPrincipal()), ledger(stock.par30Principal()), ledger(stock.par60Principal()),
                ledger(stock.par90Principal()), stock.par30Count(), stock.par60Count(), stock.par90Count(),
                ratio(stock.par90Principal(), stock.outstandingPrincipal()), stock.par90Count(), ledger(stock.par90Principal()), stock.overdueStatusCount(),
                ledger(flow.subledgerCollection()), ledger(flow.contractualDue()), collection.value(), collection.defined(),
                store.openCases(scope), ledger(store.writeOff(scope).recoveryAmount()), stock.bucketCurrent(), stock.bucket1To30(),
                stock.bucket31To60(), stock.bucket61To90(), stock.bucketAbove90(), null, null);
    }

    public BranchDashboard branch(AuthenticatedUser actor, ReportQuery query) {
        access.viewBranch(actor);
        return new BranchDashboard(false, PortfolioGroups.performance(store, scope(actor, query), GroupDimension.BRANCH, false));
    }

    public PortfolioReport portfolio(AuthenticatedUser actor, ReportQuery query) {
        access.viewFinancial(actor);
        ReportingScope scope = scope(actor, query);
        StockMetrics stock = store.stock(scope);
        FlowMetrics flow = store.flow(scope);
        DecisionMetrics decisions = store.decisions(scope);
        Rate collection = ReportingCalculator.definedRate(flow.generalLedgerCollection(), flow.contractualDue());
        Rate approval = ReportingCalculator.decisionRate(decisions.approved(), decisions.approved(), decisions.rejected());
        Rate rejection = ReportingCalculator.decisionRate(decisions.rejected(), decisions.approved(), decisions.rejected());
        LocalDate snapshot = store.latestSnapshot(ReportingScope.BANK_WIDE).orElse(null);
        return new PortfolioReport("NGN", scope.stockDate(), stock.stockBasis(), scope.bounded(), scope.startDate(), scope.endDate(), snapshot,
                ledger(flow.generalLedgerDisbursement()), flow.disbursementCount(),
                ReportingCalculator.averageLoan(flow.generalLedgerDisbursement(), flow.disbursementCount()),
                ledger(stock.outstandingPrincipal()), ledger(stock.outstandingInterest()), ledger(flow.generalLedgerCollection()),
                collection.value(), collection.defined(), ratio(stock.par30Principal(), stock.outstandingPrincipal()),
                ratio(stock.par60Principal(), stock.outstandingPrincipal()), ratio(stock.par90Principal(), stock.outstandingPrincipal()),
                ledger(stock.par30Principal()), ledger(stock.par60Principal()), ledger(stock.par90Principal()),
                ratio(stock.par90Principal(), stock.outstandingPrincipal()), stock.par90Count(), ledger(stock.par90Principal()),
                approval.value(), approval.defined(), rejection.value(), rejection.defined(),
                ReportingCalculator.turnaroundDays(decisions.turnaroundDays()), decisions.turnaroundSample(),
                PortfolioGroups.composition(store, scope, GroupDimension.SEGMENT),
                PortfolioGroups.composition(store, scope, GroupDimension.SECURITY),
                PortfolioGroups.composition(store, scope, GroupDimension.RISK),
                PortfolioGroups.performance(store, scope, GroupDimension.PRODUCT, true),
                PortfolioGroups.performance(store, scope, GroupDimension.BRANCH, true),
                Rules.standard());
    }

    public MoneyReport disbursements(AuthenticatedUser actor, ReportQuery query) {
        access.viewFinancial(actor);
        ReportingScope scope = scope(actor, query);
        FlowMetrics flow = store.flow(scope);
        return new MoneyReport(ledger(flow.generalLedgerDisbursement()), ledger(flow.subledgerDisbursement()),
                ReportingCalculator.variance(flow.subledgerDisbursement(), flow.generalLedgerDisbursement()), flow.disbursementCount(),
                ReportingRules.DISBURSEMENT, lines(ReportCode.DISBURSEMENTS, scope, query));
    }

    public MoneyReport repayments(AuthenticatedUser actor, ReportQuery query) {
        access.viewFinancial(actor);
        ReportingScope scope = scope(actor, query);
        FlowMetrics flow = store.flow(scope);
        return new MoneyReport(ledger(flow.generalLedgerCollection()), ledger(flow.subledgerCollection()),
                ReportingCalculator.variance(flow.subledgerCollection(), flow.generalLedgerCollection()), 0,
                ReportingRules.COLLECTIONS, lines(ReportCode.REPAYMENTS, scope, query));
    }

    public ParSummary par(AuthenticatedUser actor, ReportQuery query) {
        access.viewFinancial(actor);
        ReportingScope scope = scope(actor, query);
        StockMetrics stock = store.stock(scope);
        return new ParSummary(scope.stockDate(), stock.stockBasis(), ledger(stock.outstandingPrincipal()), stock.loanCount(),
                ratio(stock.par30Principal(), stock.outstandingPrincipal()), ratio(stock.par60Principal(), stock.outstandingPrincipal()),
                ratio(stock.par90Principal(), stock.outstandingPrincipal()), ledger(stock.par30Principal()), ledger(stock.par60Principal()),
                ledger(stock.par90Principal()), ReportingRules.PAR);
    }

    public DefaultReport defaults(AuthenticatedUser actor, ReportQuery query) {
        access.viewFinancial(actor);
        ReportingScope scope = scope(actor, query);
        StockMetrics stock = store.stock(scope);
        return new DefaultReport(ratio(stock.par90Principal(), stock.outstandingPrincipal()), stock.par90Count(), ledger(stock.par90Principal()),
                ledger(stock.outstandingPrincipal()), ReportingRules.DEFAULT_RATE, lines(ReportCode.DEFAULTS, scope, query));
    }

    public GroupReport branchPerformance(AuthenticatedUser actor, ReportQuery query) {
        access.viewFinancial(actor);
        return new GroupReport(ReportingRules.DISBURSEMENT, PortfolioGroups.performance(store, scope(actor, query), GroupDimension.BRANCH, true));
    }

    public GroupReport productPerformance(AuthenticatedUser actor, ReportQuery query) {
        access.viewFinancial(actor);
        return new GroupReport(ReportingRules.DISBURSEMENT, PortfolioGroups.performance(store, scope(actor, query), GroupDimension.PRODUCT, true));
    }

    public ReconciliationReport reconciliation(AuthenticatedUser actor, ReportQuery query) {
        access.viewFinancial(actor);
        ReportingScope scope = scope(actor, query);
        return new ReconciliationReport(true, ReportingRules.RECONCILIATION,
                ReportTables.reconciliation(store.balances(scope), store.flow(scope), store.writeOff(scope)));
    }

    public IncomeReport interest(AuthenticatedUser actor, ReportQuery query) {
        return income(actor, query, "4200", "Interest income", LedgerMovement.GL_INTEREST, ReportingRules.INTEREST, true);
    }

    public IncomeReport fees(AuthenticatedUser actor, ReportQuery query) {
        return income(actor, query, "4100", "Fee income", LedgerMovement.GL_FEE, ReportingRules.FEES, false);
    }

    public MoneyReport outstanding(AuthenticatedUser actor, ReportQuery query) {
        access.viewFinancial(actor);
        ReportingScope scope = scope(actor, query);
        StockMetrics stock = store.stock(scope);
        BalanceMetrics balances = store.balances(scope);
        return new MoneyReport(ledger(balances.generalLedgerPrincipal()), ledger(stock.outstandingPrincipal()),
                ReportingCalculator.variance(balances.subledgerPrincipal(), balances.generalLedgerPrincipal()), stock.loanCount(),
                stock.stockBasis(), lines(ReportCode.OUTSTANDING, scope, query));
    }

    public MoneyReport maturity(AuthenticatedUser actor, ReportQuery query) {
        access.viewFinancial(actor);
        ReportingScope scope = scope(actor, query);
        return new MoneyReport(null, null, null, 0, "Maturity uses the contractual maturity date of loans still carrying principal.",
                lines(ReportCode.MATURITY, scope, query));
    }

    public DelinquencyReport delinquency(AuthenticatedUser actor, ReportQuery query) {
        access.viewFinancial(actor);
        ReportingScope scope = scope(actor, query);
        StockMetrics stock = store.stock(scope);
        return new DelinquencyReport(stock.bucketCurrent(), stock.bucket1To30(), stock.bucket31To60(), stock.bucket61To90(),
                stock.bucketAbove90(), ledger(stock.overduePrincipal()), ReportingRules.BUCKET, lines(ReportCode.DELINQUENCY, scope, query));
    }

    public MoneyReport closures(AuthenticatedUser actor, ReportQuery query) {
        access.viewFinancial(actor);
        ReportingScope scope = scope(actor, query);
        return new MoneyReport(null, null, null, 0, "Closed loans whose closure timestamp falls in the reporting period.",
                lines(ReportCode.CLOSURES, scope, query));
    }

    public MoneyReport restructured(AuthenticatedUser actor, ReportQuery query) {
        access.viewFinancial(actor);
        ReportingScope scope = scope(actor, query);
        return new MoneyReport(null, null, null, 0, "Approved restructuring decisions in the reporting period.",
                lines(ReportCode.RESTRUCTURED, scope, query));
    }

    public WriteOffReport writeOffs(AuthenticatedUser actor, ReportQuery query) {
        access.viewFinancial(actor);
        ReportingScope scope = scope(actor, query);
        WriteOffMetrics metrics = store.writeOff(scope);
        return new WriteOffReport("5100", ledger(metrics.generalLedgerExpense()), metrics.uncollectibleCases(),
                ledger(metrics.uncollectibleExposure()), ledger(metrics.recoveryAmount()), ledger(metrics.linkedPostedRepayment()),
                ReportingCalculator.variance(metrics.recoveryAmount(), metrics.linkedPostedRepayment()), metrics.recoveryCount(),
                metrics.unpostedRecoveries(), ReportingRules.WRITE_OFF, lines(ReportCode.WRITE_OFFS, scope, query));
    }

    public List<GroupRow> exportGroups(ReportCode code, ReportingScope scope) {
        return switch (code) {
            case BRANCH_PERFORMANCE -> PortfolioGroups.performance(store, scope, GroupDimension.BRANCH, true);
            case PRODUCT_PERFORMANCE -> PortfolioGroups.performance(store, scope, GroupDimension.PRODUCT, true);
            case INTEREST_INCOME -> PortfolioGroups.income(store, scope, LedgerMovement.GL_INTEREST);
            case FEE_INCOME -> PortfolioGroups.income(store, scope, LedgerMovement.GL_FEE);
            default -> List.of();
        };
    }

    public List<List<String>> exportReconciliation(ReportingScope scope) {
        return ReportTables.cells(ReportTables.reconciliation(store.balances(scope), store.flow(scope), store.writeOff(scope)));
    }

    private IncomeReport income(AuthenticatedUser actor, ReportQuery query, String code, String name, LedgerMovement movement, String rule, boolean interest) {
        access.viewFinancial(actor);
        ReportingScope scope = scope(actor, query);
        FlowMetrics flow = store.flow(scope);
        BigDecimal generalLedger = interest ? flow.generalLedgerInterestIncome() : flow.generalLedgerFeeIncome();
        BigDecimal subledger = interest ? flow.interestSubledger() : flow.feeSubledger();
        return new IncomeReport(code, name, ledger(generalLedger), ledger(subledger), ReportingCalculator.variance(subledger, generalLedger),
                rule, PortfolioGroups.income(store, scope, movement));
    }

    private DetailPage lines(ReportCode code, ReportingScope scope, ReportQuery query) {
        PageRequest page = PageRequest.of(query.getPage(), query.getSize());
        return store.details(code, scope, page.size(), page.offset(), page.page(), page.size());
    }

    private ReportingScope scope(AuthenticatedUser actor, ReportQuery query) {
        return ReportingScope.parse(query == null ? new ReportQuery() : query, actor, zone(), clock, properties.getMaxRangeDays());
    }

    private ZoneId zone() {
        return ZoneId.of(properties.getZone());
    }

    private static BigDecimal ledger(BigDecimal value) {
        return ReportingCalculator.ledger(value);
    }

    private static BigDecimal kobo(BigDecimal value) {
        return ReportingCalculator.kobo(value);
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return ReportingCalculator.ratio(numerator, denominator);
    }
}
