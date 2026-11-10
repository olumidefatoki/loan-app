package com.mfb.lending.module.customer.application;

import java.util.Set;

import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.customer.domain.ConsentType;
import com.mfb.lending.module.customer.domain.CustomerKind;
import com.mfb.lending.module.customer.domain.DocumentType;

public final class KycRequirements {

    private KycRequirements() {
    }

    public static void assertReady(KycSnapshot snapshot) {
        if (!snapshot.profilePresent()) {
            throw ValidationException.field("profile", "A customer profile is required before KYC");
        }
        if (!snapshot.addressPresent()) {
            throw ValidationException.field("address", "A current address is required before KYC");
        }
        if (!snapshot.consents().contains(ConsentType.DATA_PROCESSING)
                || !snapshot.consents().contains(ConsentType.IDENTITY_VERIFICATION)) {
            throw ValidationException.field("consents", "Data processing and identity verification consent are required");
        }
        if (snapshot.kind() == CustomerKind.INDIVIDUAL) {
            require(snapshot.documents(), DocumentType.IDENTITY, "An identity document is required");
            require(snapshot.documents(), DocumentType.PROOF_OF_ADDRESS, "Proof of address is required");
            return;
        }
        if (snapshot.directors() < 1) {
            throw ValidationException.field("directors", "At least one director is required");
        }
        if (snapshot.beneficialOwners() < 1) {
            throw ValidationException.field("beneficialOwners", "Beneficial ownership information must be retained");
        }
        if (snapshot.authorisedRepresentatives() < 1) {
            throw ValidationException.field("authorisedRepresentatives", "An SME customer needs at least one authorised representative");
        }
        require(snapshot.documents(), DocumentType.CAC, "A CAC document is required");
        require(snapshot.documents(), DocumentType.PROOF_OF_ADDRESS, "Proof of business address is required");
        require(snapshot.documents(), DocumentType.DIRECTOR_IDENTIFICATION, "Director identification is required");
    }

    private static void require(Set<DocumentType> documents, DocumentType type, String message) {
        if (!documents.contains(type)) {
            throw ValidationException.field("documents", message);
        }
    }

    public record KycSnapshot(
            CustomerKind kind,
            boolean profilePresent,
            boolean addressPresent,
            int directors,
            int beneficialOwners,
            int authorisedRepresentatives,
            Set<ConsentType> consents,
            Set<DocumentType> documents
    ) {
        public KycSnapshot {
            consents = consents == null ? Set.of() : Set.copyOf(consents);
            documents = documents == null ? Set.of() : Set.copyOf(documents);
        }
    }
}
