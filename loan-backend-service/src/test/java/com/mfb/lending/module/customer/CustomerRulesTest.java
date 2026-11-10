package com.mfb.lending.module.customer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mfb.lending.common.exception.ApiException;
import com.mfb.lending.common.exception.ErrorCode;
import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.config.AuthProperties;
import com.mfb.lending.config.CustomerProperties;
import com.mfb.lending.module.auth.domain.CustomerType;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.domain.SecurityProfile;
import com.mfb.lending.module.auth.domain.UserRecord;
import com.mfb.lending.module.auth.domain.UserStatus;
import com.mfb.lending.module.auth.security.AesGcmCipher;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.customer.application.CustomerAccess;
import com.mfb.lending.module.customer.application.DisbursementPolicy;
import com.mfb.lending.module.customer.application.IdentityNumbers;
import com.mfb.lending.module.customer.application.IdentitySeal;
import com.mfb.lending.module.customer.application.KycRequirements;
import com.mfb.lending.module.customer.document.DocumentInspector;
import com.mfb.lending.module.customer.document.MockMalwareScanner;
import com.mfb.lending.module.customer.domain.CheckOutcome;
import com.mfb.lending.module.customer.domain.ConsentType;
import com.mfb.lending.module.customer.domain.CustomerCategory;
import com.mfb.lending.module.customer.domain.CustomerKind;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.domain.CustomerStatus;
import com.mfb.lending.module.customer.domain.DocumentType;
import com.mfb.lending.module.customer.domain.KycStatus;
import com.mfb.lending.module.customer.domain.RiskClassification;
import com.mfb.lending.module.customer.kyc.MockKycDecisions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerRulesTest {

    @Test
    void identityValuesAreNormalisedMaskedAndSealed() {
        assertThat(IdentityNumbers.bvn("221-0000-0001")).isEqualTo("22100000001");
        assertThat(IdentityNumbers.masked("22100000001")).isEqualTo("*******0001");
        assertThat(IdentityNumbers.cac("rc 123456")).isEqualTo("RC123456");
        IdentitySeal seal = new IdentitySeal(new AesGcmCipher(properties().encryptionKeyBytes()), properties());
        var sealed = seal.seal("22100000001");
        assertThat(sealed.ciphertext()).doesNotContain("22100000001");
        assertThat(seal.reveal(sealed.ciphertext())).isEqualTo("22100000001");
        assertThat(seal.hmac("22100000001")).isEqualTo(sealed.hash());
        assertThat(sealed.last4()).isEqualTo("0001");
    }

    @Test
    void disbursementStaysBlockedUntilTheCustomerIsClear() {
        CustomerRecord held = customer(CustomerStatus.ACTIVE, KycStatus.VERIFIED, true);
        assertThat(DisbursementPolicy.blockedReasons(held, true, true)).contains("COMPLIANCE_HOLD");
        CustomerRecord ready = customer(CustomerStatus.ACTIVE, KycStatus.VERIFIED, false);
        assertThat(DisbursementPolicy.blockedReasons(ready, false, true)).isEmpty();
        assertThat(DisbursementPolicy.blockedReasons(ready, false, false)).containsExactly("NO_VERIFIED_ACCOUNT");
    }

    @Test
    void kycRequirementsRejectIncompleteSmeFiles() {
        assertThatThrownBy(() -> KycRequirements.assertReady(new KycRequirements.KycSnapshot(
                CustomerKind.SME, true, true, 1, 0, 1,
                Set.of(ConsentType.DATA_PROCESSING, ConsentType.IDENTITY_VERIFICATION),
                Set.of(DocumentType.CAC, DocumentType.PROOF_OF_ADDRESS, DocumentType.DIRECTOR_IDENTIFICATION))))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void mockProviderDecisionsCoverFailureTimeoutAndScreening() {
        assertThat(MockKycDecisions.identity("00000000000", "BVN").outcome()).isEqualTo(CheckOutcome.TIMED_OUT);
        assertThat(MockKycDecisions.identity("99999999999", "BVN").outcome()).isEqualTo(CheckOutcome.FAILED);
        assertThat(MockKycDecisions.identity("22100000001", "BVN").outcome()).isEqualTo(CheckOutcome.PASSED);
        assertThat(MockKycDecisions.sanctions(List.of("Ada SANCTIONED Okonkwo")).outcome().name()).isEqualTo("MATCH");
        assertThat(MockKycDecisions.pep(List.of("Bola PEP Adeyemi")).outcome().name()).isEqualTo("MATCH");
        assertThat(MockKycDecisions.aml(List.of("HIGHRISK Ltd"), BigDecimal.ONE, BigDecimal.ONE).classification())
                .isEqualTo(RiskClassification.UNACCEPTABLE);
    }

    @Test
    void documentsRejectTheWrongTypeOversizedFilesAndMalware() {
        CustomerProperties properties = new CustomerProperties();
        properties.getDocuments().setDirectory(System.getProperty("java.io.tmpdir"));
        properties.getDocuments().setMaxBytes(2048);
        properties.getKyc().setProvider("mock");
        properties.afterPropertiesSet();
        DocumentInspector inspector = new DocumentInspector(properties, new MockMalwareScanner());
        byte[] pdf = "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        assertThat(inspector.inspect("id.pdf", "application/pdf", pdf).checksumSha256()).hasSize(64);
        assertThatThrownBy(() -> inspector.inspect("note.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DOCUMENT_REJECTED);
        byte[] large = new byte[3000];
        System.arraycopy(pdf, 0, large, 0, pdf.length);
        assertThatThrownBy(() -> inspector.inspect("big.pdf", "application/pdf", large))
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DOCUMENT_REJECTED);
        byte[] infected = "%PDF-1.4\nEICAR-STANDARD-ANTIVIRUS-TEST-FILE\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> inspector.inspect("bad.pdf", "application/pdf", infected))
                .extracting("errorCode")
                .isEqualTo(ErrorCode.MALWARE_DETECTED);
    }

    @Test
    void customersCannotSeeAnotherCustomerAndStaffStayInsideTheirBranches() {
        UUID ownerId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        CustomerRecord owned = customer(CustomerStatus.PENDING, KycStatus.NOT_STARTED, false, ownerId, branchId);
        AuthenticatedUser owner = user(ownerId, PrincipalType.CUSTOMER, CustomerType.INDIVIDUAL, List.of("CUSTOMER"), List.of(), List.of(), false);
        CustomerAccess.assertCanView(owner, owned);
        AuthenticatedUser stranger = user(UUID.randomUUID(), PrincipalType.CUSTOMER, CustomerType.INDIVIDUAL, List.of("CUSTOMER"), List.of(), List.of(), false);
        assertThatThrownBy(() -> CustomerAccess.assertCanView(stranger, owned)).isInstanceOf(ResourceNotFoundException.class);
        AuthenticatedUser officer = user(UUID.randomUUID(), PrincipalType.STAFF, CustomerType.NONE, List.of("RELATIONSHIP_OFFICER"),
                List.of("CUSTOMER_VIEW", "CUSTOMER_CREATE"), List.of(branchId), false);
        CustomerAccess.assertCanView(officer, owned);
        AuthenticatedUser otherBranch = user(UUID.randomUUID(), PrincipalType.STAFF, CustomerType.NONE, List.of("RELATIONSHIP_OFFICER"),
                List.of("CUSTOMER_VIEW"), List.of(UUID.randomUUID()), false);
        assertThatThrownBy(() -> CustomerAccess.assertCanView(otherBranch, owned)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> CustomerAccess.assertCanSearch(owner)).isInstanceOf(ForbiddenException.class);
    }

    private static CustomerRecord customer(CustomerStatus status, KycStatus kycStatus, boolean hold) {
        return customer(status, kycStatus, hold, null, UUID.randomUUID());
    }

    private static CustomerRecord customer(CustomerStatus status, KycStatus kycStatus, boolean hold, UUID userId, UUID branchId) {
        return new CustomerRecord(
                UUID.randomUUID(), "CUS10000001", CustomerKind.INDIVIDUAL, status, CustomerCategory.RETAIL,
                "Ada", "ada@example.com", "+2348012345678", userId, branchId, kycStatus, RiskClassification.LOW,
                false, null, hold, hold ? "hold" : null, Instant.EPOCH, Instant.EPOCH, "test", "test");
    }

    private static AuthenticatedUser user(
            UUID id,
            PrincipalType principalType,
            CustomerType customerType,
            List<String> roles,
            List<String> permissions,
            List<UUID> branches,
            boolean unrestricted) {
        UserRecord record = new UserRecord(
                id, principalType, customerType, "User", null, "user@example.com", "+2348000000099", "hash",
                UserStatus.ACTIVE, true, true, 0, null, Instant.EPOCH, null, false, null, null, Instant.EPOCH, Instant.EPOCH, null, null);
        return AuthenticatedUser.from(record, UUID.randomUUID(), new SecurityProfile(roles, permissions, branches, false, unrestricted));
    }

    private static AuthProperties properties() {
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setHmacSecret("dGVzdC1zdWl0ZS1qd3QtaG1hYy1zZWNyZXQtMzJiISE=");
        properties.setEncryptionKey("dGVzdC1zdWl0ZS1hZXMtMjU2LWRhdGEta2V5LTMyYiE=");
        properties.getOtp().setPepper("test-suite-otp-pepper");
        properties.afterPropertiesSet();
        return properties;
    }
}
