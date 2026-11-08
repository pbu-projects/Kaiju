package lol.pbu.kaiju.service;

import io.micronaut.data.jdbc.runtime.JdbcOperations;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import lol.pbu.kaiju.domain.AdministrativeRegion;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.dto.CreateAdministrativeRegionCommand;
import lol.pbu.kaiju.dto.IngestBoundaryResponse;
import lol.pbu.kaiju.dto.UpdateAdministrativeRegionCommand;
import lol.pbu.kaiju.model.RegionAuditAction;
import lol.pbu.kaiju.repository.AdministrativeRegionRepository;
import lol.pbu.kaiju.repository.UserRepository;
import lol.pbu.kaiju.util.SpatialMappingService;
import org.locationtech.jts.geom.Polygon;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Singleton
public class AdministrativeRegionServiceImpl implements AdministrativeRegionService {

    private static final Logger LOG = LoggerFactory.getLogger(AdministrativeRegionServiceImpl.class);

    private static final String ACTOR_NOT_FOUND = "Actor user not found";
    private static final String REGION_NOT_FOUND = "Administrative region not found";
    private static final String PARENT_NOT_FOUND = "Parent administrative region does not exist";
    private static final String INVALID_GEOMETRY = "Region geometry is topologically invalid";

    private final AdministrativeRegionRepository regionRepository;
    private final UserRepository userRepository;
    private final SpatialMappingService spatialMappingService;
    private final AuditService auditService;
    private final JdbcOperations jdbcOperations;
    private final ObjectMapper objectMapper;

    public AdministrativeRegionServiceImpl(
            AdministrativeRegionRepository regionRepository,
            UserRepository userRepository,
            SpatialMappingService spatialMappingService,
            AuditService auditService,
            JdbcOperations jdbcOperations,
            ObjectMapper objectMapper
    ) {
        this.regionRepository = regionRepository;
        this.userRepository = userRepository;
        this.spatialMappingService = spatialMappingService;
        this.auditService = auditService;
        this.jdbcOperations = jdbcOperations;
        this.objectMapper = objectMapper;
    }

    @Override
    @NonNull
    public CursoredPage<AdministrativeRegion> getAdministrativeRegions(@NonNull CursoredPageable pageable) {
        return regionRepository.findAll(pageable);
    }

    @Override
    @NonNull
    public Optional<AdministrativeRegion> getAdministrativeRegionById(@NonNull UUID id) {
        return regionRepository.findById(id);
    }

    @Override
    @Transactional
    @NonNull
    public AdministrativeRegion createAdministrativeRegion(
            @NonNull CreateAdministrativeRegionCommand command,
            @NonNull UUID actorUserId
    ) {
        User actor = resolveActor(actorUserId);

        if (command.parentRegionId() != null && !regionRepository.existsById(command.parentRegionId())) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, PARENT_NOT_FOUND);
        }

        Polygon polygon = spatialMappingService.toPolygon(command.coordinates());
        if (!polygon.isValid()) {
            throw new HttpStatusException(HttpStatus.UNPROCESSABLE_ENTITY, INVALID_GEOMETRY);
        }

        AdministrativeRegion region = new AdministrativeRegion(
                null,
                command.name(),
                command.parentRegionId(),
                polygon
        );
        AdministrativeRegion saved = regionRepository.save(region);

        String metadata = buildMetadata(Map.of("name", saved.name()));
        auditService.recordAdministrativeRegionAudit(saved.id(), actor, RegionAuditAction.CREATE, metadata);
        return saved;
    }

    @Override
    @Transactional
    @NonNull
    public AdministrativeRegion updateAdministrativeRegion(
            @NonNull UUID id,
            @NonNull UpdateAdministrativeRegionCommand command,
            @NonNull UUID actorUserId
    ) {
        User actor = resolveActor(actorUserId);

        if (command.parentRegionId() != null && id.equals(command.parentRegionId())) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "An administrative region cannot be its own parent");
        }

        if (command.parentRegionId() != null && !regionRepository.existsById(command.parentRegionId())) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, PARENT_NOT_FOUND);
        }

        AdministrativeRegion existing = regionRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, REGION_NOT_FOUND));

        Polygon polygon = spatialMappingService.toPolygon(command.coordinates());
        if (!polygon.isValid()) {
            throw new HttpStatusException(HttpStatus.UNPROCESSABLE_ENTITY, INVALID_GEOMETRY);
        }

        AdministrativeRegion updated = new AdministrativeRegion(
                id,
                command.name(),
                command.parentRegionId(),
                polygon
        );
        AdministrativeRegion saved = regionRepository.update(updated);

        String metadata = buildMetadata(Map.of("previousName", existing.name(), "newName", saved.name()));
        auditService.recordAdministrativeRegionAudit(id, actor, RegionAuditAction.UPDATE, metadata);
        return saved;
    }

    @Override
    @Transactional
    public void deleteAdministrativeRegion(@NonNull UUID id, @NonNull UUID actorUserId) {
        User actor = resolveActor(actorUserId);

        AdministrativeRegion existing = regionRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, REGION_NOT_FOUND));

        String metadata = buildMetadata(Map.of("deletedName", existing.name()));
        auditService.recordAdministrativeRegionAudit(id, actor, RegionAuditAction.DELETE, metadata);

        long deleted = regionRepository.removeById(id);
        if (deleted == 0) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, REGION_NOT_FOUND);
        }
    }

    @Override
    @Transactional
    @NonNull
    public IngestBoundaryResponse applyIngestedBoundary(
            @NonNull UUID regionId,
            @NonNull String geomJson,
            double tolerance,
            @NonNull String sourceUrl,
            @NonNull UUID actorUserId
    ) {
        User actor = resolveActor(actorUserId);

        IngestBoundaryResponse response = BoundaryIngestionService.executeBoundaryUpdate(
                jdbcOperations,
                regionId,
                geomJson,
                tolerance
        );

        String metadata = buildMetadata(Map.of(
                "sourceUrl", sourceUrl,
                "tolerance", tolerance,
                "originalPoints", response.originalPoints(),
                "simplifiedPoints", response.simplifiedPoints(),
                "reductionRatio", response.reductionRatio()
        ));
        auditService.recordAdministrativeRegionAudit(regionId, actor, RegionAuditAction.BOUNDARY_INGEST, metadata);

        return response;
    }

    private User resolveActor(UUID actorUserId) {
        return userRepository.findById(actorUserId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.UNAUTHORIZED, ACTOR_NOT_FOUND));
    }

    private String buildMetadata(Map<String, ?> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (IOException e) {
            LOG.error("Failed to serialize audit log metadata: {}", e.getMessage(), e);
            throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to serialize audit metadata");
        }
    }
}
