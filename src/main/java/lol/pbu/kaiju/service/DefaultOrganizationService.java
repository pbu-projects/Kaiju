package lol.pbu.kaiju.service;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import lol.pbu.kaiju.domain.Organization;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.model.VerificationStatus;
import lol.pbu.kaiju.repository.OrganizationRepository;
import lol.pbu.kaiju.repository.UserRepository;
import lol.pbu.kaiju.security.AuthorizationService;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Singleton
public class DefaultOrganizationService implements OrganizationService {

    private static final String ORGANIZATION_NOT_FOUND = "Organization not found";

    private static final Map<VerificationStatus, Set<VerificationStatus>> VALID_STATUS_TRANSITIONS = Map.of(
            VerificationStatus.UNVERIFIED, Set.of(
                    VerificationStatus.PENDING_REVIEW,
                    VerificationStatus.VERIFIED,
                    VerificationStatus.SUSPENDED,
                    VerificationStatus.REVOKED
            ),
            VerificationStatus.PENDING_REVIEW, Set.of(
                    VerificationStatus.UNVERIFIED,
                    VerificationStatus.VERIFIED,
                    VerificationStatus.SUSPENDED,
                    VerificationStatus.REVOKED
            ),
            VerificationStatus.VERIFIED, Set.of(
                    VerificationStatus.PENDING_REVIEW,
                    VerificationStatus.SUSPENDED,
                    VerificationStatus.REVOKED
            ),
            VerificationStatus.SUSPENDED, Set.of(
                    VerificationStatus.PENDING_REVIEW,
                    VerificationStatus.VERIFIED,
                    VerificationStatus.REVOKED
            ),
            VerificationStatus.REVOKED, Set.of()
    );

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    public DefaultOrganizationService(
            OrganizationRepository organizationRepository,
            UserRepository userRepository,
            AuthorizationService authorizationService,
            AuditService auditService
    ) {
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public CursoredPage<Organization> getOrganizations(@NonNull CursoredPageable pageable) {
        return organizationRepository.findAll(pageable);
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public Optional<Organization> getOrganizationById(@NonNull UUID id) {
        return organizationRepository.findById(id);
    }

    @Override
    @Transactional
    @NonNull
    public Organization createOrganization(@NonNull Organization organization, @NonNull UUID creatorUserId) {
        userRepository.findById(creatorUserId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.UNAUTHORIZED, "Creator user not found"));

        if (organization.parentId() != null && !organizationRepository.existsById(organization.parentId())) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Parent organization does not exist");
        }

        Organization toSave = organization.withCreationDetails(
                VerificationStatus.UNVERIFIED,
                organization.locations() != null ? organization.locations() : List.of()
        );
        return organizationRepository.save(toSave);
    }

    @Override
    @Transactional
    @NonNull
    public Organization updateOrganization(@NonNull UUID id, @NonNull Organization organization, @NonNull UUID actorUserId) {
        authorizationService.authorizeOrganizationUpdate(actorUserId, id);

        Organization existing = organizationRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, ORGANIZATION_NOT_FOUND));

        UUID parentId = organization.parentId();
        if (parentId != null) {
            if (parentId.equals(id)) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST, "An organization cannot be its own parent");
            }
            if (!organizationRepository.existsById(parentId)) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Parent organization does not exist");
            }
        }

        Organization toUpdate = existing.withUpdatedDetails(organization, parentId);
        return organizationRepository.update(toUpdate);
    }

    @Override
    @Transactional
    public void deleteOrganization(@NonNull UUID id, @NonNull UUID actorUserId) {
        authorizationService.authorizeOrganizationAdmin(actorUserId, id);
        long count = organizationRepository.removeById(id);
        if (count == 0) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, ORGANIZATION_NOT_FOUND);
        }
    }

    @Override
    @Transactional
    @NonNull
    public Organization updateVerificationStatus(
            @NonNull UUID id,
            @NonNull VerificationStatus newStatus,
            @Nullable String reason,
            @NonNull UUID actorUserId
    ) {
        User actor = authorizationService.authorizeOrganizationVerification(actorUserId);

        Organization existing = organizationRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, ORGANIZATION_NOT_FOUND));

        VerificationStatus currentStatus = existing.verificationStatus() != null
                ? existing.verificationStatus()
                : VerificationStatus.UNVERIFIED;

        Set<VerificationStatus> allowedTargets = VALID_STATUS_TRANSITIONS.getOrDefault(currentStatus, Set.of());
        if (!allowedTargets.contains(newStatus)) {
            throw new HttpStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid verification status transition from " + currentStatus + " to " + newStatus
            );
        }

        Organization updated = existing.withVerificationStatus(newStatus);
        Organization saved = organizationRepository.update(updated);
        auditService.recordOrganizationAudit(saved, actor, currentStatus.name(), newStatus.name(), reason);
        return saved;
    }
}
