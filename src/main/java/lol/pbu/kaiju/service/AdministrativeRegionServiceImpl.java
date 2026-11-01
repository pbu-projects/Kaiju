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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Singleton
public class AdministrativeRegionServiceImpl implements AdministrativeRegionService {

    private static final Logger LOG = LoggerFactory.getLogger(AdministrativeRegionServiceImpl.class);

    private static final String UPDATE_SQL = """
        WITH raw_geom AS (
            SELECT ST_SetSRID(ST_GeomFromGeoJSON(?), 4326) AS g
        ),
        simplified AS (
            SELECT
                ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_SimplifyPreserveTopology(ST_MakeValid(g), ?)), 3)) AS geom,
                ST_NPoints(g) AS orig_pts
            FROM raw_geom
        )
        UPDATE administrative_regions r
        SET geom = s.geom
        FROM simplified s
        WHERE r.id = ?
          AND ST_IsValid(s.geom::geometry)
          AND NOT ST_IsEmpty(s.geom::geometry)
        RETURNING r.name, s.orig_pts, ST_NPoints(r.geom::geometry) AS simp_pts, ST_IsValid(r.geom::geometry) AS is_valid
    """;

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
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.UNAUTHORIZED, "Actor user not found"));

        if (command.parentRegionId() != null && !regionRepository.existsById(command.parentRegionId())) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Parent administrative region does not exist");
        }

        Polygon polygon = spatialMappingService.toPolygon(command.coordinates());
        if (!polygon.isValid()) {
            throw new HttpStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Region geometry is topologically invalid");
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
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.UNAUTHORIZED, "Actor user not found"));

        if (command.parentRegionId() != null && id.equals(command.parentRegionId())) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "An administrative region cannot be its own parent");
        }

        if (command.parentRegionId() != null && !regionRepository.existsById(command.parentRegionId())) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Parent administrative region does not exist");
        }

        AdministrativeRegion existing = regionRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Administrative region not found"));

        Polygon polygon = spatialMappingService.toPolygon(command.coordinates());
        if (!polygon.isValid()) {
            throw new HttpStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Region geometry is topologically invalid");
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
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.UNAUTHORIZED, "Actor user not found"));

        AdministrativeRegion existing = regionRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Administrative region not found"));

        String metadata = buildMetadata(Map.of("deletedName", existing.name()));
        auditService.recordAdministrativeRegionAudit(id, actor, RegionAuditAction.DELETE, metadata);

        long deleted = regionRepository.removeById(id);
        if (deleted == 0) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Administrative region not found");
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
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.UNAUTHORIZED, "Actor user not found"));

        IngestBoundaryResponse response = executeBoundaryUpdate(regionId, geomJson, tolerance);

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

    private IngestBoundaryResponse executeBoundaryUpdate(UUID regionId, String geomJson, double tolerance) {
        return jdbcOperations.execute(connection -> {
            try (PreparedStatement updateStmt = connection.prepareStatement(UPDATE_SQL)) {
                updateStmt.setString(1, geomJson);
                updateStmt.setDouble(2, tolerance);
                updateStmt.setObject(3, regionId);

                try (ResultSet rs = updateStmt.executeQuery()) {
                    if (rs.next()) {
                        String regionName = rs.getString("name");
                        int origPoints = rs.getInt("orig_pts");
                        int simpPoints = rs.getInt("simp_pts");
                        boolean isValid = rs.getBoolean("is_valid");

                        double reduction = origPoints > 0 ? (1.0 - (double) simpPoints / origPoints) * 100.0 : 0.0;
                        String reductionRatio = String.format(Locale.ROOT, "%.2f%%", reduction);

                        return new IngestBoundaryResponse(
                                regionId,
                                regionName,
                                origPoints,
                                simpPoints,
                                reductionRatio,
                                isValid
                        );
                    }
                }
            } catch (SQLException e) {
                LOG.error("Failed executing atomic boundary update for region {}: {}", regionId, e.getMessage(), e);
                throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Database error updating administrative region boundary.");
            }

            boolean regionExists;
            try (PreparedStatement checkStmt = connection.prepareStatement("SELECT 1 FROM administrative_regions WHERE id = ?")) {
                checkStmt.setObject(1, regionId);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    regionExists = rs.next();
                }
            } catch (SQLException e) {
                LOG.error("Failed verifying administrative region existence for {}: {}", regionId, e.getMessage(), e);
                throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Database error verifying administrative region existence.");
            }

            if (!regionExists) {
                throw new HttpStatusException(HttpStatus.NOT_FOUND, "Administrative region not found");
            }

            throw new HttpStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Resulting geometry is topologically invalid");
        });
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
