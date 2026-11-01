package lol.pbu.kaiju.service;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import lol.pbu.kaiju.domain.AdministrativeRegion;
import lol.pbu.kaiju.dto.CreateAdministrativeRegionCommand;
import lol.pbu.kaiju.dto.IngestBoundaryResponse;
import lol.pbu.kaiju.dto.UpdateAdministrativeRegionCommand;
import org.jspecify.annotations.NonNull;

import java.util.Optional;
import java.util.UUID;

public interface AdministrativeRegionService {

    @NonNull
    CursoredPage<AdministrativeRegion> getAdministrativeRegions(@NonNull CursoredPageable pageable);

    @NonNull
    Optional<AdministrativeRegion> getAdministrativeRegionById(@NonNull UUID id);

    @NonNull
    AdministrativeRegion createAdministrativeRegion(@NonNull CreateAdministrativeRegionCommand command, @NonNull UUID actorUserId);

    @NonNull
    AdministrativeRegion updateAdministrativeRegion(@NonNull UUID id, @NonNull UpdateAdministrativeRegionCommand command, @NonNull UUID actorUserId);

    void deleteAdministrativeRegion(@NonNull UUID id, @NonNull UUID actorUserId);

    @NonNull
    IngestBoundaryResponse applyIngestedBoundary(
            @NonNull UUID regionId,
            @NonNull String geomJson,
            double tolerance,
            @NonNull String sourceUrl,
            @NonNull UUID actorUserId
    );
}
