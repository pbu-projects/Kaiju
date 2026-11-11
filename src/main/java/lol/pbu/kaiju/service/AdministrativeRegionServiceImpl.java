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
import lol.pbu.kaiju.dto.CoveringRegionDto;
import lol.pbu.kaiju.dto.CreateAdministrativeRegionCommand;
import lol.pbu.kaiju.dto.IngestBoundaryResponse;
import lol.pbu.kaiju.dto.RegionCoordinateResolution;
import lol.pbu.kaiju.dto.UpdateAdministrativeRegionCommand;
import lol.pbu.kaiju.model.RegionAuditAction;
import lol.pbu.kaiju.repository.AdministrativeRegionRepository;
import lol.pbu.kaiju.repository.UserRepository;
import lol.pbu.kaiju.util.SpatialMappingService;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.locationtech.jts.geom.Polygon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Singleton
public class AdministrativeRegionServiceImpl implements AdministrativeRegionService {

    private static final Logger LOG = LoggerFactory.getLogger(AdministrativeRegionServiceImpl.class);

    private static final String ACTOR_NOT_FOUND = "Actor user not found";
    private static final String REGION_NOT_FOUND = "Administrative region not found";
    private static final String PARENT_NOT_FOUND = "Parent administrative region does not exist";
    private static final String INVALID_GEOMETRY = "Region geometry is topologically invalid";

    private static final Pattern CITY_SUFFIX_PATTERN =
            Pattern.compile("(?i)\\s+(Downtown Core|Downtown|City Core)$");
    private static final Pattern COUNTY_SUFFIX_PATTERN =
            Pattern.compile("(?i)\\s+Region$");
    private static final Pattern STATE_SUFFIX_PATTERN =
            Pattern.compile("(?i)\\s+(State Region|State|Region)$");

    private static final Map<String, String> STATE_NAME_TO_CODE = Map.ofEntries(
            Map.entry("alabama", "AL"),
            Map.entry("alaska", "AK"),
            Map.entry("arizona", "AZ"),
            Map.entry("arkansas", "AR"),
            Map.entry("california", "CA"),
            Map.entry("colorado", "CO"),
            Map.entry("connecticut", "CT"),
            Map.entry("delaware", "DE"),
            Map.entry("florida", "FL"),
            Map.entry("georgia", "GA"),
            Map.entry("hawaii", "HI"),
            Map.entry("idaho", "ID"),
            Map.entry("illinois", "IL"),
            Map.entry("indiana", "IN"),
            Map.entry("iowa", "IA"),
            Map.entry("kansas", "KS"),
            Map.entry("kentucky", "KY"),
            Map.entry("louisiana", "LA"),
            Map.entry("maine", "ME"),
            Map.entry("maryland", "MD"),
            Map.entry("massachusetts", "MA"),
            Map.entry("michigan", "MI"),
            Map.entry("minnesota", "MN"),
            Map.entry("mississippi", "MS"),
            Map.entry("missouri", "MO"),
            Map.entry("montana", "MT"),
            Map.entry("nebraska", "NE"),
            Map.entry("nevada", "NV"),
            Map.entry("new hampshire", "NH"),
            Map.entry("new jersey", "NJ"),
            Map.entry("new mexico", "NM"),
            Map.entry("new york", "NY"),
            Map.entry("north carolina", "NC"),
            Map.entry("north dakota", "ND"),
            Map.entry("ohio", "OH"),
            Map.entry("oklahoma", "OK"),
            Map.entry("oregon", "OR"),
            Map.entry("pennsylvania", "PA"),
            Map.entry("rhode island", "RI"),
            Map.entry("south carolina", "SC"),
            Map.entry("south dakota", "SD"),
            Map.entry("tennessee", "TN"),
            Map.entry("texas", "TX"),
            Map.entry("utah", "UT"),
            Map.entry("vermont", "VT"),
            Map.entry("virginia", "VA"),
            Map.entry("washington", "WA"),
            Map.entry("west virginia", "WV"),
            Map.entry("wisconsin", "WI"),
            Map.entry("wyoming", "WY")
    );

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

        AdministrativeRegion parent = null;
        if (command.parentRegionId() != null) {
            parent = regionRepository.findById(command.parentRegionId())
                    .orElseThrow(() -> new HttpStatusException(HttpStatus.BAD_REQUEST, PARENT_NOT_FOUND));
        }

        Polygon polygon = spatialMappingService.toPolygon(command.coordinates());
        if (!polygon.isValid()) {
            throw new HttpStatusException(HttpStatus.UNPROCESSABLE_ENTITY, INVALID_GEOMETRY);
        }

        Integer level = determineLevel(parent);
        String countryCode = parent != null ? parent.countryCode() : "US";
        String stateCode = parent != null ? parent.stateCode() : null;
        String timeZone = parent != null ? parent.timeZone() : null;

        AdministrativeRegion region = new AdministrativeRegion(
                null,
                command.name(),
                command.parentRegionId(),
                polygon,
                level,
                countryCode,
                stateCode,
                timeZone
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
                polygon,
                existing.level(),
                existing.countryCode(),
                existing.stateCode(),
                existing.timeZone()
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

    @Override
    @NonNull
    public Optional<RegionCoordinateResolution> resolveCoordinates(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon) || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
            return Optional.empty();
        }

        List<CoveringRegionDto> regions = regionRepository.findCoveringRegions(lat, lon);
        if (regions == null || regions.isEmpty()) {
            return Optional.empty();
        }

        String city = regions.stream()
                .filter(r -> r.level() != null && r.level() >= 3)
                .map(CoveringRegionDto::name)
                .map(this::normalizeCityName)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        String county = regions.stream()
                .filter(r -> r.level() != null && r.level() == 2)
                .map(CoveringRegionDto::name)
                .map(this::normalizeCountyName)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        String state = regions.stream()
                .filter(r -> r.level() != null && r.level() == 1)
                .map(this::normalizeState)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        String timeZone = regions.stream()
                .map(CoveringRegionDto::timeZone)
                .filter(tz -> tz != null && !tz.isBlank())
                .findFirst()
                .orElse(null);

        if (city == null && county == null && state == null && timeZone == null) {
            return Optional.empty();
        }
        return Optional.of(new RegionCoordinateResolution(state, county, city, timeZone));
    }

    private String normalizeCityName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return CITY_SUFFIX_PATTERN.matcher(name).replaceAll("").trim();
    }

    private String normalizeCountyName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return COUNTY_SUFFIX_PATTERN.matcher(name).replaceAll("").trim();
    }

    private String normalizeState(CoveringRegionDto region) {
        if (region.stateCode() != null && !region.stateCode().isBlank()) {
            return region.stateCode().trim();
        }
        if (region.name() == null || region.name().isBlank()) {
            return null;
        }
        String stripped = STATE_SUFFIX_PATTERN.matcher(region.name()).replaceAll("").trim();
        if (stripped.length() == 2) {
            return stripped.toUpperCase();
        }
        String lookup = STATE_NAME_TO_CODE.get(stripped.toLowerCase());
        return lookup != null ? lookup : stripped;
    }

    @Override
    @NonNull
    public List<AdministrativeRegion> getStates() {
        return regionRepository.findByLevelOrderByNameAsc(1);
    }

    @Override
    @NonNull
    public Optional<AdministrativeRegion> findState(@Nullable String state) {
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        String term = state.trim();
        if (term.length() == 2) {
            Optional<AdministrativeRegion> byCode = regionRepository.findFirstByLevelAndStateCodeIgnoreCase(1, term);
            if (byCode.isPresent()) {
                return byCode;
            }
        }
        return getStates().stream()
                .filter(r -> term.equalsIgnoreCase(r.stateCode())
                        || term.equalsIgnoreCase(r.name())
                        || (r.name() != null && r.name().toLowerCase().startsWith(term.toLowerCase())))
                .findFirst();
    }

    @Override
    @NonNull
    public List<AdministrativeRegion> getCounties(@Nullable String state) {
        if (state == null || state.isBlank()) {
            return regionRepository.findByLevelOrderByNameAsc(2);
        }
        return findState(state)
                .map(s -> regionRepository.findByParentRegionIdOrderByNameAsc(s.id()))
                .orElseGet(List::of);
    }

    @Override
    @NonNull
    public List<AdministrativeRegion> getCities(@Nullable String state, @Nullable String county) {
        if (county != null && !county.isBlank()) {
            String trimmedCounty = county.trim();
            List<AdministrativeRegion> candidateCounties = (state != null && !state.isBlank())
                    ? getCounties(state)
                    : regionRepository.findByLevelOrderByNameAsc(2);

            return candidateCounties.stream()
                    .filter(c -> matchesCounty(c, trimmedCounty))
                    .findFirst()
                    .map(c -> regionRepository.findByParentRegionIdOrderByNameAsc(c.id()))
                    .orElseGet(List::of);
        }
        if (state != null && !state.isBlank()) {
            String stateCode = findState(state).map(AdministrativeRegion::stateCode).orElse(state.trim());
            return regionRepository.findByLevelAndStateCodeIgnoreCaseOrderByNameAsc(3, stateCode);
        }
        return regionRepository.findByLevelOrderByNameAsc(3);
    }

    private Integer determineLevel(@Nullable AdministrativeRegion parent) {
        if (parent == null) {
            return 1;
        }
        if (parent.level() != null) {
            return parent.level() + 1;
        }
        return 2;
    }

    private boolean matchesCounty(AdministrativeRegion candidate, String queryCounty) {
        if (candidate.name() == null) {
            return false;
        }
        String candName = candidate.name();
        if (candName.equalsIgnoreCase(queryCounty)) {
            return true;
        }
        String normalizedCand = normalizeCountyName(candName);
        if (normalizedCand != null && normalizedCand.equalsIgnoreCase(queryCounty)) {
            return true;
        }
        return candName.toLowerCase().startsWith(queryCounty.toLowerCase())
                || (normalizedCand != null && normalizedCand.toLowerCase().startsWith(queryCounty.toLowerCase()));
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
