package lol.pbu.kaiju.service;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import lol.pbu.kaiju.domain.Organization;
import lol.pbu.kaiju.model.VerificationStatus;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationService {

    @NonNull
    CursoredPage<Organization> getOrganizations(@NonNull CursoredPageable pageable);

    @NonNull
    Optional<Organization> getOrganizationById(@NonNull UUID id);

    @NonNull
    Organization createOrganization(@NonNull Organization organization, @NonNull UUID creatorUserId);

    @NonNull
    Organization updateOrganization(@NonNull UUID id, @NonNull Organization organization, @NonNull UUID actorUserId);

    void deleteOrganization(@NonNull UUID id);

    @NonNull
    Organization updateVerificationStatus(
            @NonNull UUID id,
            @NonNull VerificationStatus newStatus,
            @Nullable String reason,
            @NonNull UUID actorUserId
    );
}
