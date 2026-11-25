package lol.pbu.kaiju.service;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import lol.pbu.kaiju.domain.AdministrativeRegion;
import lol.pbu.kaiju.dto.CreateAdministrativeRegionRequest;
import lol.pbu.kaiju.dto.IngestBoundaryResponse;
import lol.pbu.kaiju.dto.UpdateAdministrativeRegionRequest;
import lol.pbu.kaiju.model.RegionCoordinateResolution;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdministrativeRegionService {

    @NonNull
    CursoredPage<AdministrativeRegion> getAdministrativeRegions(@NonNull CursoredPageable pageable);

    @NonNull
    Optional<AdministrativeRegion> getAdministrativeRegionById(@NonNull UUID id);

    @NonNull
    AdministrativeRegion createAdministrativeRegion(@NonNull CreateAdministrativeRegionRequest request, @NonNull UUID actorUserId);

    @NonNull
    AdministrativeRegion updateAdministrativeRegion(@NonNull UUID id, @NonNull UpdateAdministrativeRegionRequest request, @NonNull UUID actorUserId);

    void deleteAdministrativeRegion(@NonNull UUID id, @NonNull UUID actorUserId);

    @NonNull
    IngestBoundaryResponse applyIngestedBoundary(
            @NonNull UUID regionId,
            @NonNull String geomJson,
            double tolerance,
            @NonNull String sourceUrl,
            @NonNull UUID actorUserId
    );

    @NonNull
    Optional<RegionCoordinateResolution> resolveCoordinates(double lat, double lon);

    @NonNull
    List<AdministrativeRegion> getStates();

    @NonNull
    Optional<AdministrativeRegion> findState(@Nullable String state);

    @NonNull
    List<AdministrativeRegion> getCounties(@Nullable String state);

    @NonNull
    List<AdministrativeRegion> getCities(@Nullable String state, @Nullable String county);
}
