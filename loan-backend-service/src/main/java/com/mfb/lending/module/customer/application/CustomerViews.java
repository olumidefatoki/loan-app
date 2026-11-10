package com.mfb.lending.module.customer.application;

import java.util.List;

import com.mfb.lending.module.customer.domain.AddressRecord;
import com.mfb.lending.module.customer.domain.CustomerKind;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.domain.IndividualProfile;
import com.mfb.lending.module.customer.domain.KycCheckRecord;
import com.mfb.lending.module.customer.domain.PartyRecord;
import com.mfb.lending.module.customer.domain.ReviewCaseRecord;
import com.mfb.lending.module.customer.domain.ScreeningRecord;
import com.mfb.lending.module.customer.domain.SmeProfile;
import com.mfb.lending.module.customer.domain.StoredIdentity;
import com.mfb.lending.module.customer.dto.AddressResponse;
import com.mfb.lending.module.customer.dto.CustomerResponse;
import com.mfb.lending.module.customer.dto.CustomerSummaryResponse;
import com.mfb.lending.module.customer.dto.IndividualProfileResponse;
import com.mfb.lending.module.customer.dto.KycResponse;
import com.mfb.lending.module.customer.dto.PartyResponse;
import com.mfb.lending.module.customer.dto.ScreeningResponse;
import com.mfb.lending.module.customer.dto.SmeProfileResponse;
import com.mfb.lending.module.customer.persistence.CustomerKycRepository;
import com.mfb.lending.module.customer.persistence.CustomerProfileRepository;
import org.springframework.stereotype.Component;

@Component
public class CustomerViews {

    private final CustomerProfileRepository profiles;
    private final CustomerKycRepository kyc;
    private final CustomerEligibilityService eligibility;

    public CustomerViews(CustomerProfileRepository profiles, CustomerKycRepository kyc, CustomerEligibilityService eligibility) {
        this.profiles = profiles;
        this.kyc = kyc;
        this.eligibility = eligibility;
    }

    public CustomerSummaryResponse summary(CustomerRecord customer) {
        return new CustomerSummaryResponse(
                customer.id(),
                customer.reference(),
                customer.kind().name(),
                customer.status().name(),
                customer.category().name(),
                customer.displayName(),
                customer.email(),
                customer.phoneNumber(),
                customer.branchId(),
                customer.kycStatus().name(),
                customer.riskClassification().name(),
                customer.blacklisted(),
                customer.complianceHold());
    }

    public CustomerResponse detail(CustomerRecord customer) {
        IndividualProfile individual = customer.kind() == CustomerKind.INDIVIDUAL
                ? profiles.findIndividual(customer.id()).orElse(null) : null;
        SmeProfile sme = customer.kind() == CustomerKind.SME ? profiles.findSme(customer.id()).orElse(null) : null;
        List<String> reasons = eligibility.blockedReasons(customer);
        return new CustomerResponse(
                customer.id(),
                customer.reference(),
                customer.kind().name(),
                customer.status().name(),
                customer.category().name(),
                customer.displayName(),
                customer.email(),
                customer.phoneNumber(),
                customer.userId(),
                customer.branchId(),
                customer.kycStatus().name(),
                customer.riskClassification().name(),
                customer.blacklisted(),
                customer.blacklistReason(),
                customer.complianceHold(),
                customer.complianceHoldReason(),
                reasons.isEmpty(),
                reasons,
                individual == null ? null : individual(individual),
                sme == null ? null : sme(sme),
                profiles.addresses(customer.id()).stream().map(CustomerViews::address).toList(),
                customer.kind() == CustomerKind.SME ? parties(profiles.directors(customer.id())) : List.of(),
                customer.kind() == CustomerKind.SME ? parties(profiles.owners(customer.id())) : List.of(),
                customer.kind() == CustomerKind.SME ? parties(profiles.representatives(customer.id())) : List.of(),
                customer.createdAt(),
                customer.updatedAt());
    }

    public KycResponse kyc(CustomerRecord customer) {
        List<String> reasons = eligibility.blockedReasons(customer);
        return new KycResponse(
                customer.id(),
                customer.kycStatus().name(),
                customer.complianceHold(),
                customer.complianceHoldReason(),
                reasons.isEmpty(),
                reasons,
                kyc.checks(customer.id()).stream().map(CustomerViews::check).toList(),
                kyc.reviews(customer.id()).stream().map(CustomerViews::review).toList());
    }

    public ScreeningResponse screening(CustomerRecord customer) {
        return kyc.risk(customer.id())
                .map(risk -> new ScreeningResponse(
                        customer.id(),
                        risk.classification().name(),
                        risk.score(),
                        results(customer)))
                .orElseGet(() -> new ScreeningResponse(
                        customer.id(),
                        customer.riskClassification().name(),
                        null,
                        results(customer)));
    }

    private List<ScreeningResponse.Result> results(CustomerRecord customer) {
        return kyc.screenings(customer.id()).stream()
                .map(CustomerViews::screeningResult)
                .toList();
    }

    private static IndividualProfileResponse individual(IndividualProfile profile) {
        return new IndividualProfileResponse(
                profile.fullName(),
                profile.dateOfBirth(),
                profile.phoneNumber(),
                profile.email(),
                masked(profile.bvn()),
                masked(profile.nin()),
                profile.employmentStatus().name(),
                profile.employerName(),
                profile.occupation(),
                profile.monthlyIncome(),
                profile.monthlyExpenses(),
                profile.nextOfKinName(),
                profile.nextOfKinRelationship(),
                profile.nextOfKinPhone(),
                profile.nextOfKinAddress());
    }

    private static SmeProfileResponse sme(SmeProfile profile) {
        return new SmeProfileResponse(
                profile.registeredName(),
                profile.tradingName(),
                profile.cacNumber(),
                profile.businessType().name(),
                profile.businessSector(),
                profile.incorporationDate(),
                profile.annualTurnover(),
                profile.monthlyRevenue(),
                profile.operatingExpenses(),
                masked(profile.tin()));
    }

    private static List<PartyResponse> parties(List<PartyRecord> parties) {
        return parties.stream().map(CustomerViews::party).toList();
    }

    private static PartyResponse party(PartyRecord party) {
        return new PartyResponse(
                party.id(),
                party.fullName(),
                party.dateOfBirth(),
                party.phoneNumber(),
                party.email(),
                party.position(),
                party.ownershipPercentage(),
                masked(party.bvn()),
                masked(party.nin()));
    }

    private static AddressResponse address(AddressRecord address) {
        return new AddressResponse(
                address.id(),
                address.type().name(),
                address.line1(),
                address.line2(),
                address.city(),
                address.state(),
                address.countryCode(),
                address.postalCode());
    }

    private static KycResponse.Check check(KycCheckRecord check) {
        return new KycResponse.Check(
                check.id(),
                check.type().name(),
                check.outcome().name(),
                check.provider(),
                check.reference(),
                check.detail(),
                check.completedAt());
    }

    private static KycResponse.ReviewCase review(ReviewCaseRecord review) {
        return new KycResponse.ReviewCase(
                review.id(),
                review.type().name(),
                review.status(),
                review.mandatoryHold(),
                review.summary(),
                review.openedAt());
    }

    private static ScreeningResponse.Result screeningResult(ScreeningRecord screening) {
        return new ScreeningResponse.Result(
                screening.id(),
                screening.type().name(),
                screening.outcome().name(),
                screening.provider(),
                screening.detail(),
                screening.screenedAt());
    }

    private static String masked(StoredIdentity identity) {
        return identity == null ? null : identity.masked();
    }
}
