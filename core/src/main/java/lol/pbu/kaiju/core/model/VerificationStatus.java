package lol.pbu.kaiju.core.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public enum VerificationStatus {
    UNVERIFIED,
    PENDING_REVIEW,
    VERIFIED,
    SUSPENDED,
    REVOKED
}
