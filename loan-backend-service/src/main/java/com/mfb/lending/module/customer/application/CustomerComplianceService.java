package com.mfb.lending.module.customer.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.customer.domain.AccountRecord;
import com.mfb.lending.module.customer.domain.AccountVerificationStatus;
import com.mfb.lending.module.customer.domain.AddressType;
import com.mfb.lending.module.customer.domain.CheckOutcome;
import com.mfb.lending.module.customer.domain.CheckType;
import com.mfb.lending.module.customer.domain.CustomerKind;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.domain.IndividualProfile;
import com.mfb.lending.module.customer.domain.KycCheckRecord;
import com.mfb.lending.module.customer.domain.KycStatus;
import com.mfb.lending.module.customer.domain.PartyRecord;
import com.mfb.lending.module.customer.domain.ReviewCaseRecord;
import com.mfb.lending.module.customer.domain.ReviewCaseType;
import com.mfb.lending.module.customer.domain.RiskClassification;
import com.mfb.lending.module.customer.domain.ScreeningOutcome;
import com.mfb.lending.module.customer.domain.ScreeningRecord;
import com.mfb.lending.module.customer.domain.ScreeningType;
import com.mfb.lending.module.customer.domain.SealedIdentifier;
import com.mfb.lending.module.customer.domain.SmeProfile;
import com.mfb.lending.module.customer.dto.AccountResponse;
import com.mfb.lending.module.customer.dto.AccountVerifyRequest;
import com.mfb.lending.module.customer.dto.KycResponse;
import com.mfb.lending.module.customer.dto.ScreeningResponse;
import com.mfb.lending.module.customer.kyc.AmlDecision;
import com.mfb.lending.module.customer.kyc.AmlRiskAssessor;
import com.mfb.lending.module.customer.kyc.BankAccountVerifier;
import com.mfb.lending.module.customer.kyc.BvnVerifier;
import com.mfb.lending.module.customer.kyc.CacVerifier;
import com.mfb.lending.module.customer.kyc.NinVerifier;
import com.mfb.lending.module.customer.kyc.PepScreener;
import com.mfb.lending.module.customer.kyc.SanctionsScreener;
import com.mfb.lending.module.customer.kyc.ScreeningDecision;
import com.mfb.lending.module.customer.kyc.VerificationResult;
import com.mfb.lending.module.customer.persistence.CustomerAccountRepository;
import com.mfb.lending.module.customer.persistence.CustomerDocumentRepository;
import com.mfb.lending.module.customer.persistence.CustomerKycRepository;
import com.mfb.lending.module.customer.persistence.CustomerProfileRepository;
import com.mfb.lending.module.customer.persistence.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CustomerComplianceService {

    private final CustomerRepository customers;
    private final CustomerProfileRepository profiles;
    private final CustomerDocumentRepository documents;
    private final CustomerAccountRepository accounts;
    private final CustomerKycRepository kyc;
    private final CustomerViews views;
    private final IdentitySeal seal;
    private final BvnVerifier bvnVerifier;
    private final NinVerifier ninVerifier;
    private final CacVerifier cacVerifier;
    private final BankAccountVerifier bankAccountVerifier;
    private final SanctionsScreener sanctionsScreener;
    private final PepScreener pepScreener;
    private final AmlRiskAssessor amlRiskAssessor;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public CustomerComplianceService(
            CustomerRepository customers,
            CustomerProfileRepository profiles,
            CustomerDocumentRepository documents,
            CustomerAccountRepository accounts,
            CustomerKycRepository kyc,
            CustomerViews views,
            IdentitySeal seal,
            BvnVerifier bvnVerifier,
            NinVerifier ninVerifier,
            CacVerifier cacVerifier,
            BankAccountVerifier bankAccountVerifier,
            SanctionsScreener sanctionsScreener,
            PepScreener pepScreener,
            AmlRiskAssessor amlRiskAssessor,
            AuditService audit,
            Clock clock,
            TransactionTemplate transactions) {
        this.customers = customers;
        this.profiles = profiles;
        this.documents = documents;
        this.accounts = accounts;
        this.kyc = kyc;
        this.views = views;
        this.seal = seal;
        this.bvnVerifier = bvnVerifier;
        this.ninVerifier = ninVerifier;
        this.cacVerifier = cacVerifier;
        this.bankAccountVerifier = bankAccountVerifier;
        this.sanctionsScreener = sanctionsScreener;
        this.pepScreener = pepScreener;
        this.amlRiskAssessor = amlRiskAssessor;
        this.audit = audit;
        this.clock = clock;
        this.transactions = transactions;
    }

    public KycResponse run(UUID id, AuthenticatedUser actor, ClientContext client) {
        CustomerRecord customer = require(id, actor, true);
        Outcome outcome = customer.kind() == CustomerKind.INDIVIDUAL ? individual(customer) : sme(customer);
        transactions.executeWithoutResult(status -> persist(customer, outcome, actor.getUsername()));
        CustomerRecord saved = customers.findById(id).orElseThrow(() -> new ResourceNotFoundException("Customer"));
        audit.record("CUSTOMER_KYC_COMPLETED", "customer", id.toString(), actor.getUsername(), client, Map.of(
                "kycStatus", saved.kycStatus().name(),
                "complianceHold", saved.complianceHold(),
                "riskClassification", saved.riskClassification().name()));
        return views.kyc(saved);
    }

    public KycResponse kyc(UUID id, AuthenticatedUser actor) {
        return views.kyc(require(id, actor, false));
    }

    public ScreeningResponse screening(UUID id, AuthenticatedUser actor) {
        return views.screening(require(id, actor, false));
    }

    public AccountResponse verifyAccount(UUID id, AccountVerifyRequest request, AuthenticatedUser actor, ClientContext client) {
        CustomerRecord customer = require(id, actor, true);
        String bankCode = IdentityNumbers.bankCode(request.bankCode());
        String accountNumber = IdentityNumbers.accountNumber(request.accountNumber());
        VerificationResult result = bankAccountVerifier.verify(bankCode, accountNumber, customer.displayName());
        SealedIdentifier sealed = seal.seal(accountNumber);
        AccountVerificationStatus verificationStatus = switch (result.outcome()) {
            case PASSED -> AccountVerificationStatus.VERIFIED;
            case FAILED -> AccountVerificationStatus.FAILED;
            case TIMED_OUT -> AccountVerificationStatus.TIMED_OUT;
        };
        Instant now = Instant.now(clock);
        AccountRecord account = new AccountRecord(
                UUID.randomUUID(),
                id,
                bankCode,
                BankDirectory.name(bankCode),
                sealed.masked(),
                verificationStatus == AccountVerificationStatus.VERIFIED ? customer.displayName() : null,
                verificationStatus,
                verificationStatus == AccountVerificationStatus.VERIFIED ? now : null);
        transactions.executeWithoutResult(status -> {
            require(id, actor, true);
            accounts.upsert(account, sealed);
            if (verificationStatus != AccountVerificationStatus.VERIFIED) {
                kyc.insertReview(new ReviewCaseRecord(
                        UUID.randomUUID(),
                        id,
                        verificationStatus == AccountVerificationStatus.TIMED_OUT ? ReviewCaseType.PROVIDER_TIMEOUT : ReviewCaseType.KYC_FAILURE,
                        "OPEN",
                        false,
                        result.detail(),
                        now), null);
            }
        });
        audit.record("CUSTOMER_ACCOUNT_VERIFIED", "customer", id.toString(), actor.getUsername(), client, Map.of(
                "bankCode", bankCode,
                "verificationStatus", verificationStatus.name()));
        AccountRecord stored = accounts.list(id).stream()
                .filter(item -> bankCode.equals(item.bankCode()) && sealed.masked().equals(item.maskedNumber()))
                .reduce((first, second) -> second)
                .orElse(account);
        return accountResponse(stored);
    }

    public List<AccountResponse> accounts(UUID id, AuthenticatedUser actor) {
        require(id, actor, false);
        return accounts.list(id).stream().map(CustomerComplianceService::accountResponse).toList();
    }

    private static AccountResponse accountResponse(AccountRecord account) {
        return new AccountResponse(
                account.id(),
                account.bankCode(),
                account.bankName(),
                account.maskedNumber(),
                account.accountName(),
                "NGN",
                account.verificationStatus().name(),
                account.verifiedAt());
    }

    private Outcome individual(CustomerRecord customer) {
        IndividualProfile profile = profiles.findIndividual(customer.id()).orElse(null);
        KycRequirements.assertReady(snapshot(customer, profile != null, AddressType.RESIDENTIAL));
        String bvn = seal.reveal(profile.bvn().ciphertext());
        String nin = seal.reveal(profile.nin().ciphertext());
        List<KycCheckRecord> checks = List.of(
                check(customer.id(), CheckType.BVN, bvnVerifier.verify(bvn, profile.fullName(), profile.dateOfBirth())),
                check(customer.id(), CheckType.NIN, ninVerifier.verify(nin, profile.fullName(), profile.dateOfBirth())));
        List<String> names = List.of(customer.displayName(), profile.fullName());
        return screen(customer, names, profile.monthlyIncome(), profile.monthlyExpenses(), checks);
    }

    private Outcome sme(CustomerRecord customer) {
        SmeProfile profile = profiles.findSme(customer.id()).orElse(null);
        KycRequirements.assertReady(snapshot(customer, profile != null, AddressType.BUSINESS));
        List<KycCheckRecord> checks = List.of(
                check(customer.id(), CheckType.CAC, cacVerifier.verify(profile.cacNumber(), profile.registeredName())));
        List<String> names = new ArrayList<>();
        names.add(profile.registeredName());
        if (profile.tradingName() != null) {
            names.add(profile.tradingName());
        }
        profiles.directors(customer.id()).forEach(party -> names.add(party.fullName()));
        profiles.owners(customer.id()).forEach(party -> names.add(party.fullName()));
        profiles.representatives(customer.id()).forEach(party -> names.add(party.fullName()));
        return screen(customer, names, profile.monthlyRevenue(), profile.operatingExpenses(), checks);
    }

    private Outcome screen(CustomerRecord customer, List<String> names, java.math.BigDecimal income, java.math.BigDecimal expenses, List<KycCheckRecord> checks) {
        ScreeningDecision sanctions = sanctionsScreener.screen(names);
        ScreeningDecision pep = pepScreener.screen(names);
        AmlDecision aml = amlRiskAssessor.assess(names, income, expenses);
        return new Outcome(checks, sanctions, pep, aml);
    }

    private void persist(CustomerRecord customer, Outcome outcome, String actor) {
        customers.lockById(customer.id()).orElseThrow(() -> new ResourceNotFoundException("Customer"));
        Instant now = Instant.now(clock);
        boolean identityFailed = false;
        boolean identityTimeout = false;
        for (KycCheckRecord check : outcome.checks()) {
            kyc.insertCheck(check);
            if (check.outcome() == CheckOutcome.FAILED) {
                identityFailed = true;
                openReview(customer.id(), check.id(), ReviewCaseType.KYC_FAILURE, true, check.detail(), now);
            } else if (check.outcome() == CheckOutcome.TIMED_OUT) {
                identityTimeout = true;
                openReview(customer.id(), check.id(), ReviewCaseType.PROVIDER_TIMEOUT, true, check.detail(), now);
            }
        }
        ScreeningOutcome sanctions = outcome.sanctions().outcome();
        ScreeningOutcome pep = outcome.pep().outcome();
        kyc.insertScreening(screening(customer.id(), ScreeningType.SANCTIONS, sanctions, outcome.sanctions().detail(), now));
        kyc.insertScreening(screening(customer.id(), ScreeningType.PEP, pep, outcome.pep().detail(), now));
        ScreeningOutcome amlOutcome = amlOutcome(outcome.aml().classification());
        kyc.insertScreening(screening(customer.id(), ScreeningType.AML, amlOutcome, outcome.aml().detail(), now));
        if (sanctions == ScreeningOutcome.MATCH) {
            openReview(customer.id(), null, ReviewCaseType.SANCTIONS_MATCH, true, outcome.sanctions().detail(), now);
        }
        if (pep == ScreeningOutcome.MATCH) {
            openReview(customer.id(), null, ReviewCaseType.PEP_MATCH, true, outcome.pep().detail(), now);
        }
        if (outcome.aml().classification().atLeast(RiskClassification.MEDIUM)) {
            boolean mandatory = outcome.aml().classification().atLeast(RiskClassification.HIGH);
            openReview(customer.id(), null, ReviewCaseType.AML_RISK, mandatory, outcome.aml().detail(), now);
        }
        RiskClassification risk = outcome.aml().classification();
        int score = outcome.aml().score();
        if (sanctions == ScreeningOutcome.MATCH) {
            risk = RiskClassification.UNACCEPTABLE;
            score = 100;
        } else if (pep == ScreeningOutcome.MATCH) {
            risk = risk.worse(RiskClassification.HIGH);
            score = Math.max(score, 75);
        }
        boolean hold = identityFailed || identityTimeout || sanctions == ScreeningOutcome.MATCH
                || pep == ScreeningOutcome.MATCH || outcome.aml().classification().atLeast(RiskClassification.HIGH);
        KycStatus kycStatus = identityFailed ? KycStatus.FAILED : hold ? KycStatus.REVIEW : KycStatus.VERIFIED;
        String reason = hold ? clip(holdReason(outcome, identityFailed, identityTimeout)) : null;
        kyc.upsertRisk(customer.id(), risk, score, outcome.aml().factors(), actor);
        customers.updateKycState(customer.id(), kycStatus, risk, hold, reason, actor);
    }

    private KycRequirements.KycSnapshot snapshot(CustomerRecord customer, boolean profilePresent, AddressType addressType) {
        boolean address = profiles.addresses(customer.id()).stream().anyMatch(item -> item.type() == addressType);
        return new KycRequirements.KycSnapshot(
                customer.kind(),
                profilePresent,
                address,
                profiles.directors(customer.id()).size(),
                profiles.owners(customer.id()).size(),
                profiles.representatives(customer.id()).size(),
                Set.copyOf(profiles.grantedConsents(customer.id())),
                Set.copyOf(documents.types(customer.id())));
    }

    private KycCheckRecord check(UUID customerId, CheckType type, VerificationResult result) {
        return new KycCheckRecord(
                UUID.randomUUID(),
                customerId,
                type,
                result.outcome(),
                result.provider(),
                result.reference(),
                result.detail(),
                Instant.now(clock));
    }

    private static ScreeningRecord screening(UUID customerId, ScreeningType type, ScreeningOutcome outcome, String detail, Instant screenedAt) {
        return new ScreeningRecord(UUID.randomUUID(), customerId, type, outcome, "mock", detail, screenedAt);
    }

    private void openReview(UUID customerId, UUID kycId, ReviewCaseType type, boolean mandatory, String summary, Instant openedAt) {
        kyc.insertReview(new ReviewCaseRecord(UUID.randomUUID(), customerId, type, "OPEN", mandatory, clip(summary), openedAt), kycId);
    }

    private static ScreeningOutcome amlOutcome(RiskClassification classification) {
        if (classification.atLeast(RiskClassification.HIGH)) {
            return ScreeningOutcome.MATCH;
        }
        if (classification == RiskClassification.MEDIUM) {
            return ScreeningOutcome.REVIEW;
        }
        return ScreeningOutcome.CLEAR;
    }

    private static String holdReason(Outcome outcome, boolean identityFailed, boolean identityTimeout) {
        List<String> reasons = new ArrayList<>();
        if (identityFailed) {
            reasons.add("Identity verification failed");
        }
        if (identityTimeout) {
            reasons.add("Verification provider timed out");
        }
        if (outcome.sanctions().outcome() == ScreeningOutcome.MATCH) {
            reasons.add(outcome.sanctions().detail());
        }
        if (outcome.pep().outcome() == ScreeningOutcome.MATCH) {
            reasons.add(outcome.pep().detail());
        }
        if (outcome.aml().classification().atLeast(RiskClassification.HIGH)) {
            reasons.add(outcome.aml().detail());
        }
        return String.join("; ", reasons);
    }

    private CustomerRecord require(UUID id, AuthenticatedUser actor, boolean mutate) {
        CustomerRecord customer = customers.findById(id).orElseThrow(() -> new ResourceNotFoundException("Customer"));
        if (mutate) {
            CustomerAccess.assertCanMutate(actor, customer);
        } else {
            CustomerAccess.assertCanView(actor, customer);
        }
        return customer;
    }

    private static String clip(String value) {
        if (value == null || value.length() <= 500) {
            return value;
        }
        return value.substring(0, 500);
    }

    private record Outcome(
            List<KycCheckRecord> checks,
            ScreeningDecision sanctions,
            ScreeningDecision pep,
            AmlDecision aml
    ) {
    }
}
