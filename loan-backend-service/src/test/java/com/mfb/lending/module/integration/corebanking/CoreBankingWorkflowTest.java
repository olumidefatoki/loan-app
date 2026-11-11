package com.mfb.lending.module.integration.corebanking;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.common.exception.ApiException;
import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ErrorCode;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.config.CoreBankingProperties;
import com.mfb.lending.module.loanapplication.domain.ApplicationStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoreBankingWorkflowTest {

    @Test
    void customerEnquirySucceedsAgainstTheMock() {
        Harness harness = harness(5);
        var customer = harness.customers.find(harness.loan.customerId().toString());
        assertThat(customer.verified()).isTrue();
        assertThat(customer.customerReference()).isEqualTo(harness.loan.customerId().toString());
    }

    @Test
    void failedAccountEnquiryDoesNotCreateADisbursement() {
        Harness harness = harness(5);
        harness.registry.failOperation(CoreOperation.VALIDATE_ACCOUNT, CoreOutcome.NOT_FOUND);
        assertThatThrownBy(() -> harness.accounts.validate(harness.loan.customerId().toString(), harness.loan.accountId().toString()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> harness.disbursements.disburse(instruction(harness, "disburse-account"), null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(harness.store.count(null, null)).isZero();
        assertThat(harness.loans.disbursed).isFalse();
    }

    @Test
    void successfulDisbursementIsIdempotent() {
        Harness harness = harness(5);
        CoreTransaction first = harness.disbursements.disburse(instruction(harness, "disburse-001"), null);
        CoreTransaction second = harness.disbursements.disburse(instruction(harness, "disburse-001"), null);
        assertThat(first.state()).isEqualTo(FinancialTransactionState.SUCCESSFUL);
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(harness.client.calls(CoreOperation.SUBMIT_DISBURSEMENT)).isEqualTo(1);
        assertThat(harness.client.calls(CoreOperation.CREATE_LOAN_ACCOUNT)).isEqualTo(1);
        assertThat(harness.loans.disbursed).isTrue();
        assertThatThrownBy(() -> harness.disbursements.disburse(instruction(harness, "disburse-002"), null))
                .isInstanceOf(ConflictException.class);
        assertThat(harness.client.calls(CoreOperation.SUBMIT_DISBURSEMENT)).isEqualTo(1);
    }

    @Test
    void timeoutBecomesUnknownUntilEnquiryShowsItWasNotPosted() {
        Harness harness = harness(5);
        harness.registry.timeout("disburse-timeout");
        CoreTransaction timedOut = harness.disbursements.disburse(instruction(harness, "disburse-timeout"), null);
        assertThat(timedOut.state()).isEqualTo(FinancialTransactionState.UNKNOWN);
        assertThat(harness.loans.disbursed).isFalse();
        assertThat(harness.client.calls(CoreOperation.SUBMIT_DISBURSEMENT)).isEqualTo(1);
        CoreTransaction resolved = harness.enquiry.enquire(timedOut.id());
        assertThat(resolved.state()).isEqualTo(FinancialTransactionState.FAILED);
        assertThat(harness.client.calls(CoreOperation.SUBMIT_DISBURSEMENT)).isEqualTo(1);
        assertThat(harness.loans.disbursed).isFalse();
    }

    @Test
    void lostResponseIsResolvedByEnquiryAndThenDisburses() {
        Harness harness = harness(5);
        harness.registry.lostResponse("disburse-lost");
        CoreTransaction lost = harness.disbursements.disburse(instruction(harness, "disburse-lost"), null);
        assertThat(lost.state()).isEqualTo(FinancialTransactionState.UNKNOWN);
        assertThat(harness.loans.disbursed).isFalse();
        CoreTransaction confirmed = harness.enquiry.enquire(lost.id());
        assertThat(confirmed.state()).isEqualTo(FinancialTransactionState.SUCCESSFUL);
        assertThat(harness.loans.disbursed).isTrue();
        assertThat(harness.client.calls(CoreOperation.SUBMIT_DISBURSEMENT)).isEqualTo(1);
    }

    @Test
    void authenticationFailureDoesNotRetryTheInstruction() {
        Harness harness = harness(5);
        harness.registry.authenticationFailure(true);
        CoreTransaction failed = harness.disbursements.disburse(instruction(harness, "disburse-auth"), null);
        assertThat(failed.state()).isEqualTo(FinancialTransactionState.FAILED);
        assertThat(harness.client.calls(CoreOperation.CREATE_LOAN_ACCOUNT)).isEqualTo(1);
        assertThat(harness.client.calls(CoreOperation.SUBMIT_DISBURSEMENT)).isZero();
        CoreTransaction again = harness.disbursements.disburse(instruction(harness, "disburse-auth"), null);
        assertThat(again.id()).isEqualTo(failed.id());
        assertThat(harness.client.calls(CoreOperation.CREATE_LOAN_ACCOUNT)).isEqualTo(1);
    }

    @Test
    void downtimeDoesNotSendTheInstructionAgain() {
        Harness harness = harness(1);
        harness.registry.unavailable(true);
        CoreTransaction unknown = harness.disbursements.disburse(instruction(harness, "disburse-down"), null);
        assertThat(unknown.state()).isEqualTo(FinancialTransactionState.UNKNOWN);
        assertThat(harness.circuit.position()).isEqualTo("OPEN");
        assertThat(harness.client.calls(CoreOperation.SUBMIT_DISBURSEMENT)).isZero();
        CoreTransaction retry = harness.disbursements.disburse(instruction(harness, "disburse-down"), null);
        assertThat(retry.state()).isEqualTo(FinancialTransactionState.UNKNOWN);
        assertThat(harness.client.calls(CoreOperation.CREATE_LOAN_ACCOUNT)).isEqualTo(1);
        harness.registry.unavailable(false);
        assertThatThrownBy(() -> harness.enquiry.enquire(unknown.id()))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void repaymentReversalAndDuplicateCallbackAreIdempotent() throws Exception {
        Harness harness = harness(5);
        harness.disbursements.disburse(instruction(harness, "disburse-pay"), null);
        CoreTransaction repayment = harness.repayments.repay(repayment(harness, "repay-001", "400.00"), null);
        CoreTransaction duplicate = harness.repayments.repay(repayment(harness, "repay-001", "400.00"), null);
        assertThat(repayment.state()).isEqualTo(FinancialTransactionState.SUCCESSFUL);
        assertThat(duplicate.id()).isEqualTo(repayment.id());
        assertThat(harness.client.calls(CoreOperation.SUBMIT_REPAYMENT)).isEqualTo(1);
        assertThat(harness.store.postedNet(harness.loan.applicationId())).isEqualByComparingTo("600.00");
        assertThatThrownBy(() -> harness.repayments.repay(repayment(harness, "repay-over", "700.00"), null))
                .isInstanceOf(com.mfb.lending.common.exception.ValidationException.class);

        CoreTransaction reversed = harness.repayments.reverse(repayment.id(), "reverse-001", harness.actorId, "officer@mfb.test", null);
        assertThat(reversed.state()).isEqualTo(FinancialTransactionState.REVERSED);
        assertThat(harness.store.postedNet(harness.loan.applicationId())).isEqualByComparingTo("1000.00");
        assertThat(harness.client.calls(CoreOperation.REVERSAL)).isEqualTo(1);

        harness.registry.seedPosting("PROV-1", new BigDecimal("200.00"));
        byte[] body = webhook(harness, "msg-1", "REPAYMENT_POSTED", null, "repay-wh-01", "200.00", "PROV-1");
        var first = post(harness, "msg-1", body);
        var second = post(harness, "msg-1", body);
        assertThat(first.duplicate()).isFalse();
        assertThat(first.transaction().state()).isEqualTo(FinancialTransactionState.SUCCESSFUL);
        assertThat(second.duplicate()).isTrue();
        assertThat(harness.store.postedNet(harness.loan.applicationId())).isEqualByComparingTo("800.00");

        CoreTransaction posted = harness.repayments.repay(repayment(harness, "repay-002", "100.00"), null);
        harness.registry.markReversed(posted.reference());
        byte[] reversal = webhook(harness, "msg-2", "REPAYMENT_REVERSED", posted.reference(), "unused-key", "100.00", null);
        var reversedEvent = post(harness, "msg-2", reversal);
        assertThat(reversedEvent.transaction().state()).isEqualTo(FinancialTransactionState.REVERSED);
        assertThat(harness.store.postedNet(harness.loan.applicationId())).isEqualByComparingTo("800.00");
    }

    @Test
    void servicingCollectionCanIncludeInterestAboveRemainingPrincipal() {
        Harness harness = harness(5);
        harness.disbursements.disburse(instruction(harness, "disburse-servicing"), null);
        CoreTransaction collected = harness.repayments.collectForServicing(
                repayment(harness, "srv-collect", "1200.00"), new BigDecimal("1200.00"), null);
        assertThat(collected.state()).isEqualTo(FinancialTransactionState.SUCCESSFUL);
        assertThat(harness.store.servicingCollection(collected.id())).isTrue();
        assertThat(harness.store.postedNet(harness.loan.applicationId())).isEqualByComparingTo("-200.00");
        assertThat(harness.store.servicingRepaymentCash(harness.loan.applicationId())).isEqualByComparingTo("1200.00");
        assertThatThrownBy(() -> harness.repayments.repay(repayment(harness, "srv-direct", "1.00"), null))
                .isInstanceOf(com.mfb.lending.common.exception.ValidationException.class);
    }

    @Test
    void reconciliationMismatchIsRecordedAndDeadLettersResolveOnlyAfterEnquiry() {
        Harness harness = harness(5);
        CoreTransaction posted = harness.disbursements.disburse(instruction(harness, "disburse-recon"), null);
        harness.registry.mismatch(posted.reference(), new BigDecimal("1.00"));
        var mismatch = harness.enquiry.reconcile(posted.id());
        assertThat(mismatch.itemStatus()).isEqualTo("MISMATCH");
        assertThat(mismatch.transaction().state()).isEqualTo(FinancialTransactionState.RECONCILIATION_REQUIRED);

        Harness timed = harness(5);
        timed.registry.timeout("disburse-dead");
        CoreTransaction unknown = timed.disbursements.disburse(instruction(timed, "disburse-dead"), null);
        assertThat(timed.store.countDeadLetters()).isEqualTo(1);
        timed.registry.failOperation(CoreOperation.DISBURSEMENT_STATUS, CoreOutcome.UNKNOWN);
        assertThat(timed.deadLetters.process()).isZero();
        assertThat(timed.store.countDeadLetters()).isEqualTo(1);
        timed.registry.failOperation(CoreOperation.DISBURSEMENT_STATUS, CoreOutcome.NOT_FOUND);
        assertThat(timed.deadLetters.process()).isEqualTo(1);
        assertThat(timed.store.countDeadLetters()).isZero();
        assertThat(timed.store.require(unknown.id()).state()).isEqualTo(FinancialTransactionState.FAILED);
        assertThat(timed.client.calls(CoreOperation.SUBMIT_DISBURSEMENT)).isEqualTo(1);
    }

    private static WebhookIngestionService.WebhookResult post(Harness harness, String messageId, byte[] body) {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = WebhookSignature.sign("test-secret", timestamp, body);
        return harness.webhooks.accept(body, messageId, timestamp, signature);
    }

    private static byte[] webhook(
            Harness harness,
            String messageId,
            String eventType,
            String transactionReference,
            String idempotencyKey,
            String amount,
            String providerReference) throws Exception {
        var payload = new WebhookIngestionService.WebhookPayload(
                messageId,
                eventType,
                harness.loan.applicationId(),
                transactionReference,
                idempotencyKey,
                new BigDecimal(amount),
                "NGN",
                providerReference);
        return new ObjectMapper().writeValueAsBytes(payload);
    }

    private static DisbursementInstruction instruction(Harness harness, String key) {
        return new DisbursementInstruction(
                harness.loan.applicationId(), key, null, null, harness.actorId, "officer@mfb.test", null);
    }

    private static RepaymentInstruction repayment(Harness harness, String key, String amount) {
        return new RepaymentInstruction(
                harness.loan.applicationId(), key, new BigDecimal(amount), null, harness.actorId, "officer@mfb.test", null);
    }

    private static Harness harness(int threshold) {
        Clock clock = Clock.systemUTC();
        InMemoryCoreBankingStore store = new InMemoryCoreBankingStore(clock);
        MockScenarioRegistry registry = new MockScenarioRegistry();
        MockCoreBankingClient client = new MockCoreBankingClient(registry);
        CoreBankingMapper mapper = new CoreBankingMapper(new ObjectMapper());
        IntegrationCircuitBreaker circuit = new IntegrationCircuitBreaker(threshold, Duration.ofMinutes(1));
        SafeRetry retry = new SafeRetry(2, Duration.ZERO, Duration.ZERO, 1, duration -> {
        });
        CoreBankingMetrics metrics = new CoreBankingMetrics(new SimpleMeterRegistry());
        UnitOfWork unitOfWork = new InlineUnitOfWork();
        CoreBankingAdapter adapter = new DefaultCoreBankingAdapter(client, mapper, store, circuit, retry, metrics, clock);
        FakeLoans loans = new FakeLoans();
        CorePostingEngine engine = new CorePostingEngine(store, adapter, loans, circuit, metrics, unitOfWork, clock);
        CoreBankingOutboxDispatcher dispatcher = new CoreBankingOutboxDispatcher(store, engine, unitOfWork, clock);
        CoreDisbursementService disbursements = new CoreDisbursementService(store, adapter, engine, loans, dispatcher, unitOfWork, null, clock);
        CoreRepaymentService repayments = new CoreRepaymentService(store, engine, dispatcher, unitOfWork, null, clock);
        CoreBankingProperties properties = new CoreBankingProperties();
        properties.getWebhook().setSecret("test-secret");
        properties.validate();
        WebhookIngestionService webhooks = new WebhookIngestionService(
                properties, store, repayments, disbursements, unitOfWork, new ObjectMapper(), clock);
        return new Harness(
                store,
                registry,
                client,
                loans,
                circuit,
                disbursements,
                repayments,
                new CoreCustomerService(adapter),
                new CoreAccountService(adapter),
                new CoreTransactionEnquiryService(store, engine),
                new DeadLetterProcessor(store, engine),
                webhooks,
                loans.loan,
                UUID.randomUUID());
    }

    private record Harness(
            InMemoryCoreBankingStore store,
            MockScenarioRegistry registry,
            MockCoreBankingClient client,
            FakeLoans loans,
            IntegrationCircuitBreaker circuit,
            CoreDisbursementService disbursements,
            CoreRepaymentService repayments,
            CoreCustomerService customers,
            CoreAccountService accounts,
            CoreTransactionEnquiryService enquiry,
            DeadLetterProcessor deadLetters,
            WebhookIngestionService webhooks,
            DisbursementLoanPort.DisbursableLoan loan,
            UUID actorId
    ) {
    }

    private static final class FakeLoans implements DisbursementLoanPort {
        private final DisbursableLoan loan = new DisbursableLoan(
                UUID.randomUUID(),
                "LA000000000001",
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("1000.00"),
                "NGN",
                ApplicationStatus.PENDING_DISBURSEMENT);
        private boolean disbursed;

        @Override
        public DisbursableLoan load(UUID applicationId, UUID accountId, BigDecimal amount) {
            return loan;
        }

        @Override
        public void markDisbursed(UUID applicationId, String reference, UUID actorId, String actorName) {
            disbursed = true;
        }
    }

    private static final class InlineUnitOfWork implements UnitOfWork {
        @Override
        public <T> T required(Supplier<T> work) {
            return work.get();
        }
    }
}
