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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Singleton
public class OrganizationServiceImpl implements OrganizationService {

    private static final String ORGANIZATION_NOT_FOUND = "Organization not found";

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public OrganizationServiceImpl(
            OrganizationRepository organizationRepository,
            UserRepository userRepository,
            AuditService auditService
    ) {
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
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

        Organization toSave = new Organization(
                null,
                organization.name(),
                organization.websiteUrl(),
                organization.parentId(),
                organization.isPublic(),
                VerificationStatus.UNVERIFIED,
                null,
                organization.locations() != null ? organization.locations() : List.of()
        );
        return organizationRepository.save(toSave);
    }

    @Override
    @Transactional
    @NonNull
    public Organization updateOrganization(@NonNull UUID id, @NonNull Organization organization, @NonNull UUID actorUserId) {
        userRepository.findById(actorUserId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.UNAUTHORIZED, "Actor user not found"));

        Organization existing = organizationRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, ORGANIZATION_NOT_FOUND));

        Organization toUpdate = new Organization(
                id,
                organization.name(),
                organization.websiteUrl(),
                organization.parentId(),
                organization.isPublic(),
                existing.verificationStatus(),
                existing.verificationExpiresAt(),
                existing.locations()
        );
        return organizationRepository.update(toUpdate);
    }

    @Override
    @Transactional
    public void deleteOrganization(@NonNull UUID id) {
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
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.UNAUTHORIZED, "Actor user not found"));

        Organization existing = organizationRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, ORGANIZATION_NOT_FOUND));

        String previousStatus = existing.verificationStatus() != null
                ? existing.verificationStatus().name()
                : VerificationStatus.UNVERIFIED.name();

        Organization updated = new Organization(
                existing.id(),
                existing.name(),
                existing.websiteUrl(),
                existing.parentId(),
                existing.isPublic(),
                newStatus,
                existing.verificationExpiresAt(),
                existing.locations()
        );
        Organization saved = organizationRepository.update(updated);
        auditService.recordOrganizationAudit(saved, actor, previousStatus, newStatus.name(), reason);
        return saved;
    }
}
