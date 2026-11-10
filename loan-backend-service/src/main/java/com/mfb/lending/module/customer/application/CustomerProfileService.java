package com.mfb.lending.module.customer.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.customer.domain.AddressRecord;
import com.mfb.lending.module.customer.domain.AddressType;
import com.mfb.lending.module.customer.domain.BusinessType;
import com.mfb.lending.module.customer.domain.ClientChannel;
import com.mfb.lending.module.customer.domain.ConsentType;
import com.mfb.lending.module.customer.domain.CustomerKind;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.domain.EmploymentStatus;
import com.mfb.lending.module.customer.domain.IndividualProfile;
import com.mfb.lending.module.customer.domain.PartyRecord;
import com.mfb.lending.module.customer.domain.SealedIdentifier;
import com.mfb.lending.module.customer.domain.SmeProfile;
import com.mfb.lending.module.customer.dto.AddressRequest;
import com.mfb.lending.module.customer.dto.BeneficialOwnerRequest;
import com.mfb.lending.module.customer.dto.ConsentRequest;
import com.mfb.lending.module.customer.dto.CustomerResponse;
import com.mfb.lending.module.customer.dto.DirectorRequest;
import com.mfb.lending.module.customer.dto.IndividualProfileRequest;
import com.mfb.lending.module.customer.dto.PartyResponse;
import com.mfb.lending.module.customer.dto.RepresentativeRequest;
import com.mfb.lending.module.customer.dto.SmeProfileRequest;
import com.mfb.lending.module.customer.persistence.CustomerProfileRepository;
import com.mfb.lending.module.customer.persistence.CustomerRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CustomerProfileService {

    private final CustomerRepository customers;
    private final CustomerProfileRepository profiles;
    private final CustomerViews views;
    private final IdentitySeal seal;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public CustomerProfileService(
            CustomerRepository customers,
            CustomerProfileRepository profiles,
            CustomerViews views,
            IdentitySeal seal,
            AuditService audit,
            Clock clock,
            TransactionTemplate transactions) {
        this.customers = customers;
        this.profiles = profiles;
        this.views = views;
        this.seal = seal;
        this.audit = audit;
        this.clock = clock;
        this.transactions = transactions;
    }

    public CustomerResponse saveIndividual(
            UUID id,
            IndividualProfileRequest request,
            AuthenticatedUser actor,
            ClientContext client,
            ClientChannel channel) {
        String bvn = IdentityNumbers.bvn(request.bvn());
        String nin = IdentityNumbers.nin(request.nin());
        SealedIdentifier sealedBvn = seal.seal(bvn);
        SealedIdentifier sealedNin = seal.seal(nin);
        EmploymentStatus employment = EmploymentStatus.valueOf(request.employmentStatus());
        if (employment == EmploymentStatus.EMPLOYED && (request.employerName() == null || request.employerName().isBlank())) {
            throw ValidationException.field("employerName", "employerName is required");
        }
        assertAdult(request.dateOfBirth(), "dateOfBirth");
        try {
            transactions.executeWithoutResult(status -> {
                CustomerRecord customer = lock(id, actor, CustomerKind.INDIVIDUAL);
                CustomerConflicts.assertDifferentCustomer(profiles.customerIdByBvnHash(sealedBvn.hash()), id, "A customer with this BVN is already registered");
                CustomerConflicts.assertDifferentCustomer(profiles.customerIdByNinHash(sealedNin.hash()), id, "A customer with this NIN is already registered");
                profiles.upsertIndividual(new IndividualProfile(
                        id,
                        request.fullName().trim(),
                        request.dateOfBirth(),
                        IdentityNumbers.phone(request.phoneNumber(), "phoneNumber"),
                        IdentityNumbers.email(request.email(), "email"),
                        null,
                        null,
                        employment,
                        blank(request.employerName()),
                        blank(request.occupation()),
                        request.monthlyIncome(),
                        request.monthlyExpenses(),
                        request.nextOfKin().fullName().trim(),
                        request.nextOfKin().relationship().trim(),
                        IdentityNumbers.phone(request.nextOfKin().phoneNumber(), "nextOfKin.phoneNumber"),
                        request.nextOfKin().address().trim()), sealedBvn, sealedNin);
                profiles.upsertAddress(address(id, AddressType.RESIDENTIAL, request.residentialAddress()));
                saveConsents(id, request.consents(), channel);
                customers.rename(id, request.fullName().trim(), IdentityNumbers.email(request.email(), "email"),
                        IdentityNumbers.phone(request.phoneNumber(), "phoneNumber"), actor.getUsername());
            });
        } catch (DuplicateKeyException ex) {
            throw CustomerConflicts.translate(ex);
        }
        audit.record("CUSTOMER_PROFILE_SAVED", "customer", id.toString(), actor.getUsername(), client, Map.of("customerType", "INDIVIDUAL"));
        return views.detail(customers.findById(id).orElseThrow());
    }

    public CustomerResponse saveSme(
            UUID id,
            SmeProfileRequest request,
            AuthenticatedUser actor,
            ClientContext client,
            ClientChannel channel) {
        String cac = IdentityNumbers.cac(request.cacRegistrationNumber());
        String tin = IdentityNumbers.tin(request.taxIdentificationNumber());
        SealedIdentifier sealedTin = seal.seal(tin);
        if (request.incorporationDate() != null && request.incorporationDate().isAfter(LocalDate.now(clock))) {
            throw ValidationException.field("incorporationDate", "incorporationDate cannot be in the future");
        }
        List<PartyRecord> representatives = request.authorisedRepresentatives().stream()
                .map(person -> representative(id, person))
                .toList();
        try {
            transactions.executeWithoutResult(status -> {
                CustomerRecord customer = lock(id, actor, CustomerKind.SME);
                CustomerConflicts.assertDifferentCustomer(profiles.customerIdByCac(cac), id, "A customer with this CAC registration number is already registered");
                CustomerConflicts.assertDifferentCustomer(profiles.customerIdByTinHash(sealedTin.hash()), id, "A customer with this tax identification number is already registered");
                profiles.upsertSme(new SmeProfile(
                        id,
                        request.registeredBusinessName().trim(),
                        blank(request.tradingName()),
                        cac,
                        BusinessType.valueOf(request.businessType()),
                        request.businessSector().trim(),
                        request.incorporationDate(),
                        request.annualTurnover(),
                        request.monthlyRevenue(),
                        request.operatingExpenses(),
                        null), sealedTin);
                profiles.upsertAddress(address(id, AddressType.BUSINESS, request.businessAddress()));
                profiles.replaceRepresentatives(id, representatives);
                saveConsents(id, request.consents(), channel);
                customers.rename(
                        id,
                        request.registeredBusinessName().trim(),
                        customer.email(),
                        customer.phoneNumber(),
                        actor.getUsername());
            });
        } catch (DuplicateKeyException ex) {
            throw CustomerConflicts.translate(ex);
        }
        audit.record("CUSTOMER_PROFILE_SAVED", "customer", id.toString(), actor.getUsername(), client, Map.of(
                "customerType", "SME",
                "authorisedRepresentatives", representatives.size()));
        return views.detail(customers.findById(id).orElseThrow());
    }

    public PartyResponse addDirector(UUID id, DirectorRequest request, AuthenticatedUser actor, ClientContext client) {
        assertAdult(request.dateOfBirth(), "dateOfBirth");
        SealedIdentifier bvn = seal.seal(IdentityNumbers.bvn(request.bvn()));
        SealedIdentifier nin = request.nin() == null || request.nin().isBlank() ? null : seal.seal(IdentityNumbers.nin(request.nin()));
        PartyRecord party = new PartyRecord(
                UUID.randomUUID(),
                id,
                request.fullName().trim(),
                request.dateOfBirth(),
                IdentityNumbers.phone(request.phoneNumber(), "phoneNumber"),
                blankEmail(request.email()),
                blank(request.position()) == null ? "DIRECTOR" : request.position().trim(),
                null,
                null,
                null);
        try {
            transactions.executeWithoutResult(status -> {
                lock(id, actor, CustomerKind.SME);
                profiles.insertDirector(party, bvn, nin);
            });
        } catch (DuplicateKeyException ex) {
            throw CustomerConflicts.translate(ex);
        }
        audit.record("CUSTOMER_DIRECTOR_ADDED", "customer", id.toString(), actor.getUsername(), client, Map.of("directorId", party.id().toString()));
        return partyResponse(party, bvn, nin);
    }

    public PartyResponse addBeneficialOwner(UUID id, BeneficialOwnerRequest request, AuthenticatedUser actor, ClientContext client) {
        assertAdult(request.dateOfBirth(), "dateOfBirth");
        SealedIdentifier bvn = seal.seal(IdentityNumbers.bvn(request.bvn()));
        SealedIdentifier nin = request.nin() == null || request.nin().isBlank() ? null : seal.seal(IdentityNumbers.nin(request.nin()));
        PartyRecord party = new PartyRecord(
                UUID.randomUUID(),
                id,
                request.fullName().trim(),
                request.dateOfBirth(),
                IdentityNumbers.phone(request.phoneNumber(), "phoneNumber"),
                blankEmail(request.email()),
                null,
                request.ownershipPercentage(),
                null,
                null);
        try {
            transactions.executeWithoutResult(status -> {
                lock(id, actor, CustomerKind.SME);
                profiles.insertOwner(party, bvn, nin);
            });
        } catch (DuplicateKeyException ex) {
            throw CustomerConflicts.translate(ex);
        }
        audit.record("CUSTOMER_BENEFICIAL_OWNER_ADDED", "customer", id.toString(), actor.getUsername(), client, Map.of("beneficialOwnerId", party.id().toString()));
        return partyResponse(party, bvn, nin);
    }

    private CustomerRecord lock(UUID id, AuthenticatedUser actor, CustomerKind expected) {
        CustomerRecord customer = customers.lockById(id).orElseThrow(() -> new com.mfb.lending.common.exception.ResourceNotFoundException("Customer"));
        CustomerAccess.assertCanMutate(actor, customer);
        if (customer.kind() != expected) {
            throw ValidationException.field("customerType", "This profile does not match the customer type");
        }
        return customer;
    }

    private AddressRecord address(UUID customerId, AddressType type, AddressRequest request) {
        String country = request.countryCode() == null || request.countryCode().isBlank()
                ? "NG"
                : request.countryCode().trim().toUpperCase(Locale.ROOT);
        return new AddressRecord(
                UUID.randomUUID(),
                customerId,
                type,
                request.line1().trim(),
                blank(request.line2()),
                request.city().trim(),
                request.state().trim(),
                country,
                blank(request.postalCode()));
    }

    private PartyRecord representative(UUID customerId, RepresentativeRequest request) {
        return new PartyRecord(
                UUID.randomUUID(),
                customerId,
                request.fullName().trim(),
                null,
                IdentityNumbers.phone(request.phoneNumber(), "phoneNumber"),
                blankEmail(request.email()),
                request.position().trim(),
                null,
                null,
                null);
    }

    private void saveConsents(UUID customerId, List<ConsentRequest> consents, ClientChannel channel) {
        if (consents == null) {
            return;
        }
        for (ConsentRequest consent : consents) {
            profiles.upsertConsent(
                    UUID.randomUUID(),
                    customerId,
                    ConsentType.valueOf(consent.type()),
                    consent.granted(),
                    consent.version().trim(),
                    channel.name(),
                    consent.granted() ? Instant.now(clock) : null);
        }
    }

    private void assertAdult(LocalDate dateOfBirth, String field) {
        LocalDate today = LocalDate.now(clock);
        if (dateOfBirth == null || !dateOfBirth.isBefore(today) || Period.between(dateOfBirth, today).getYears() < 18) {
            throw ValidationException.field(field, field + " must be at least 18 years ago");
        }
    }

    private static PartyResponse partyResponse(PartyRecord party, SealedIdentifier bvn, SealedIdentifier nin) {
        return new PartyResponse(
                party.id(),
                party.fullName(),
                party.dateOfBirth(),
                party.phoneNumber(),
                party.email(),
                party.position(),
                party.ownershipPercentage(),
                bvn.masked(),
                nin == null ? null : nin.masked());
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String blankEmail(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
