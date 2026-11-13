package lol.pbu.kaiju.service;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.jdbc.runtime.JdbcOperations;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.json.tree.JsonNode;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lol.pbu.kaiju.dto.UsSyncResult;
import lol.pbu.kaiju.service.UsStateCatalog.UsStateEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Syncs US state, county, and place boundaries from Census TIGERweb into PostGIS.
 */
@Singleton
public class UsBoundarySyncService {

    private static final Logger LOG = LoggerFactory.getLogger(UsBoundarySyncService.class);

    private static final double STATE_TOLERANCE = BoundaryIngestionService.DEFAULT_TOLERANCE;
    private static final double DETAIL_TOLERANCE = 0.0002;
    private static final int STATE_PAGE_SIZE = 10;
    private static final int DETAIL_PAGE_SIZE = 100;
    private static final UUID US_AUDIT_REGION_ID = UUID.nameUUIDFromBytes("US-STATES-SYNC".getBytes(StandardCharsets.UTF_8));

    private static final String FIND_STATE_ID_SQL = """
            SELECT id
            FROM administrative_regions
            WHERE level = 1
              AND (geoid = ? OR state_code = ?)
            ORDER BY CASE WHEN geoid = ? THEN 0 ELSE 1 END
            LIMIT 1
            """;

    private static final String INSERT_AUDIT_LOG_SQL = """
            INSERT INTO administrative_region_audit_logs (region_id, actor_id, action, metadata)
            VALUES (?, ?, 'BOUNDARY_INGEST', CAST(? AS jsonb))
            """;

    private final BoundaryIngestionService boundaryIngestionService;
    private final JdbcOperations jdbcOperations;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final int statesLayerId;
    private final int countiesLayerId;
    private final int placesLayerId;
    private final int cdpsLayerId;
    private final Semaphore syncSemaphore = new Semaphore(2);

    @Inject
    public UsBoundarySyncService(
            BoundaryIngestionService boundaryIngestionService,
            JdbcOperations jdbcOperations,
            ObjectMapper objectMapper,
            @Value("${kaiju.tigerweb.base-url:https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer}") String baseUrl,
            @Value("${kaiju.tigerweb.layers.states:84}") int statesLayerId,
            @Value("${kaiju.tigerweb.layers.counties:82}") int countiesLayerId,
            @Value("${kaiju.tigerweb.layers.places:28}") int placesLayerId,
            @Value("${kaiju.tigerweb.layers.cdps:30}") int cdpsLayerId
    ) {
        this.boundaryIngestionService = boundaryIngestionService;
        this.jdbcOperations = jdbcOperations;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.statesLayerId = statesLayerId;
        this.countiesLayerId = countiesLayerId;
        this.placesLayerId = placesLayerId;
        this.cdpsLayerId = cdpsLayerId;
    }

    public UsBoundarySyncService(
            BoundaryIngestionService boundaryIngestionService,
            JdbcOperations jdbcOperations,
            ObjectMapper objectMapper
    ) {
        this(
                boundaryIngestionService,
                jdbcOperations,
                objectMapper,
                "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer",
                84,
                82,
                28,
                30
        );
    }

    /**
     * Syncs all US states, DC, and territories (Level 1) from TIGERweb.
     */
    public UsSyncResult syncStates(@Nullable UUID actorUserId) {
        return withConcurrencyGuard(() -> {
            SyncCounts counts = new SyncCounts();
            int offset = 0;

            while (true) {
                String url = buildQueryUrl(statesLayerId, "1=1", offset, STATE_PAGE_SIZE, STATE_TOLERANCE);
                JsonNode root = boundaryIngestionService.fetchAndParseJson(url);
                JsonNode features = root.get("features");
                if (features == null || !features.isArray() || features.size() == 0) {
                    break;
                }

                features.values().forEach(feature -> processStateFeature(feature, counts));

                if (!isExceededTransferLimit(root)) {
                    break;
                }
                offset += features.size();
            }

            UsSyncResult result = counts.toResult("US", 1);
            recordAuditLog(US_AUDIT_REGION_ID, actorUserId, result);
            return result;
        });
    }

    /**
     * Syncs all counties (Level 2) for a US state or territory.
     */
    public UsSyncResult syncStateCounties(String stateCode, @Nullable UUID actorUserId) {
        UsStateEntry entry = requireStateEntry(stateCode);
        return withConcurrencyGuard(() -> {
            UUID stateId = requireStateRegionId(entry);
            SyncCounts counts = new SyncCounts();
            String where = "STATE='" + entry.fipsCode() + "'";
            int offset = 0;

            while (true) {
                String url = buildQueryUrl(countiesLayerId, where, offset, DETAIL_PAGE_SIZE, null);
                JsonNode root = boundaryIngestionService.fetchAndParseJson(url);
                JsonNode features = root.get("features");
                if (features == null || !features.isArray() || features.size() == 0) {
                    break;
                }

                features.values().forEach(feature -> processCountyFeature(feature, entry, stateId, counts));

                if (!isExceededTransferLimit(root)) {
                    break;
                }
                offset += features.size();
            }

            UsSyncResult result = counts.toResult(entry.stateCode(), 2);
            recordAuditLog(stateId, actorUserId, result);
            return result;
        });
    }

    /**
     * Syncs incorporated places and optionally Census Designated Places (Level 3) for a state.
     */
    public UsSyncResult syncStatePlaces(String stateCode, boolean includeCdps, @Nullable UUID actorUserId) {
        UsStateEntry entry = requireStateEntry(stateCode);
        return withConcurrencyGuard(() -> {
            UUID stateId = requireStateRegionId(entry);
            SyncCounts counts = new SyncCounts();
            String where = "STATE='" + entry.fipsCode() + "'";

            syncPlacesLayer(placesLayerId, where, entry, stateId, counts);
            if (includeCdps || entry.cdpOnlyForPlaces()) {
                syncPlacesLayer(cdpsLayerId, where, entry, stateId, counts);
            }

            UsSyncResult result = counts.toResult(entry.stateCode(), 3);
            recordAuditLog(stateId, actorUserId, result);
            return result;
        });
    }

    private void syncPlacesLayer(int layerId, String where, UsStateEntry entry, UUID stateId, SyncCounts counts) {
        int offset = 0;
        while (true) {
            String url = buildQueryUrl(layerId, where, offset, DETAIL_PAGE_SIZE, null);
            JsonNode root = boundaryIngestionService.fetchAndParseJson(url);
            JsonNode features = root.get("features");
            if (features == null || !features.isArray() || features.size() == 0) {
                break;
            }

            features.values().forEach(feature -> processPlaceFeature(feature, entry, stateId, counts));

            if (!isExceededTransferLimit(root)) {
                break;
            }
            offset += features.size();
        }
    }

    private void processStateFeature(JsonNode feature, SyncCounts counts) {
        String geoid = readProperty(feature, "GEOID");
        if (geoid == null) {
            geoid = readProperty(feature, "STATE");
        }
        Optional<UsStateEntry> catalogEntry = UsStateCatalog.findByFipsCode(geoid);
        if (catalogEntry.isEmpty()) {
            counts.skipped++;
            return;
        }
        UsStateEntry entry = catalogEntry.get();
        String name = Optional.ofNullable(readProperty(feature, "NAME")).orElse(entry.name());

        try {
            String geomJson = boundaryIngestionService.parseAndValidateFeatureGeometry(feature);
            boolean inserted = boundaryIngestionService.upsertRegionByGeoid(
                    entry.fipsCode(),
                    name,
                    null,
                    1,
                    "US",
                    entry.stateCode(),
                    geomJson,
                    STATE_TOLERANCE
            );
            counts.recordUpsert(inserted);
        } catch (Exception e) {
            LOG.warn("Failed syncing state {} ({}): {}", entry.stateCode(), entry.fipsCode(), e.getMessage());
            counts.failed++;
            counts.errors.add(entry.stateCode() + ": " + e.getMessage());
        }
    }

    private void processCountyFeature(JsonNode feature, UsStateEntry entry, UUID stateId, SyncCounts counts) {
        String geoid = readProperty(feature, "GEOID");
        String name = readProperty(feature, "NAME");
        if (geoid == null || name == null) {
            counts.skipped++;
            return;
        }

        try {
            String geomJson = boundaryIngestionService.parseAndValidateFeatureGeometry(feature);
            boolean inserted = boundaryIngestionService.upsertRegionByGeoid(
                    geoid,
                    name,
                    stateId,
                    2,
                    "US",
                    entry.stateCode(),
                    geomJson,
                    DETAIL_TOLERANCE
            );
            counts.recordUpsert(inserted);
        } catch (Exception e) {
            LOG.warn("Failed syncing county {} ({}): {}", name, geoid, e.getMessage());
            counts.failed++;
            counts.errors.add(geoid + ": " + e.getMessage());
        }
    }

    private void processPlaceFeature(JsonNode feature, UsStateEntry entry, UUID stateId, SyncCounts counts) {
        String geoid = readProperty(feature, "GEOID");
        String name = readProperty(feature, "NAME");
        if (geoid == null || name == null) {
            counts.skipped++;
            return;
        }

        try {
            String geomJson = boundaryIngestionService.parseAndValidateFeatureGeometry(feature);
            boolean inserted = boundaryIngestionService.upsertPlaceByGeoid(
                    geoid,
                    name,
                    stateId,
                    entry.stateCode(),
                    geomJson,
                    DETAIL_TOLERANCE
            );
            counts.recordUpsert(inserted);
        } catch (Exception e) {
            LOG.warn("Failed syncing place {} ({}): {}", name, geoid, e.getMessage());
            counts.failed++;
            counts.errors.add(geoid + ": " + e.getMessage());
        }
    }

    private UsStateEntry requireStateEntry(String stateCode) {
        return UsStateCatalog.findByStateCode(stateCode)
                .orElseThrow(() -> new HttpStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Unknown US state or territory code: " + stateCode
                ));
    }

    private UUID requireStateRegionId(UsStateEntry entry) {
        UUID stateId = jdbcOperations.execute(connection -> {
            try (PreparedStatement stmt = connection.prepareStatement(FIND_STATE_ID_SQL)) {
                stmt.setString(1, entry.fipsCode());
                stmt.setString(2, entry.stateCode());
                stmt.setString(3, entry.fipsCode());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getObject("id", UUID.class);
                    }
                }
            } catch (SQLException e) {
                throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed looking up state region: " + e.getMessage());
            }
            return null;
        });

        if (stateId == null) {
            throw new HttpStatusException(
                    HttpStatus.NOT_FOUND,
                    "State region not found for " + entry.stateCode() + ". Run state sync first."
            );
        }
        return stateId;
    }

    private String buildQueryUrl(int layerId, String where, int offset, int recordCount, @Nullable Double maxAllowableOffset) {
        StringBuilder sb = new StringBuilder();
        sb.append(baseUrl).append('/').append(layerId).append("/query")
                .append("?where=").append(URLEncoder.encode(where, StandardCharsets.UTF_8))
                .append("&outFields=GEOID,NAME,STATE")
                .append("&orderByFields=GEOID+ASC")
                .append("&resultOffset=").append(offset)
                .append("&resultRecordCount=").append(recordCount)
                .append("&f=geojson");
        if (maxAllowableOffset != null) {
            sb.append("&maxAllowableOffset=").append(maxAllowableOffset);
        }
        return sb.toString();
    }

    private boolean isExceededTransferLimit(JsonNode root) {
        JsonNode direct = root.get("exceededTransferLimit");
        if (direct != null && direct.isBoolean()) {
            return direct.getBooleanValue();
        }
        JsonNode props = root.get("properties");
        if (props != null && props.isObject()) {
            JsonNode nested = props.get("exceededTransferLimit");
            if (nested != null && nested.isBoolean()) {
                return nested.getBooleanValue();
            }
        }
        return false;
    }

    @Nullable
    private String readProperty(JsonNode feature, String key) {
        JsonNode props = feature.get("properties");
        if (props == null || !props.isObject()) {
            return null;
        }
        JsonNode value = props.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.getStringValue();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private void recordAuditLog(UUID regionId, @Nullable UUID actorUserId, UsSyncResult result) {
        String metadataJson;
        try {
            metadataJson = objectMapper.writeValueAsString(Map.of(
                    "stateCode", result.stateCode(),
                    "level", result.level(),
                    "created", result.created(),
                    "updated", result.updated(),
                    "skipped", result.skipped(),
                    "failed", result.failed()
            ));
        } catch (IOException e) {
            metadataJson = "{}";
        }

        String finalMetadataJson = metadataJson;
        jdbcOperations.execute(connection -> {
            try (PreparedStatement stmt = connection.prepareStatement(INSERT_AUDIT_LOG_SQL)) {
                stmt.setObject(1, regionId);
                stmt.setObject(2, actorUserId);
                stmt.setString(3, finalMetadataJson);
                stmt.executeUpdate();
            } catch (SQLException e) {
                LOG.warn("Failed recording boundary sync audit log for {}: {}", regionId, e.getMessage());
            }
            return null;
        });
    }

    private <T> T withConcurrencyGuard(Supplier<T> action) {
        boolean acquired = false;
        try {
            acquired = syncSemaphore.tryAcquire(5, TimeUnit.SECONDS);
            if (!acquired) {
                throw new HttpStatusException(HttpStatus.TOO_MANY_REQUESTS, "Boundary sync is busy. Please try again shortly.");
            }
            return action.get();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Boundary sync was interrupted.");
        } finally {
            if (acquired) {
                syncSemaphore.release();
            }
        }
    }

    private static final class SyncCounts {
        int created;
        int updated;
        int skipped;
        int failed;
        final List<String> errors = new ArrayList<>();

        void recordUpsert(boolean inserted) {
            if (inserted) {
                created++;
            } else {
                updated++;
            }
        }

        UsSyncResult toResult(String stateCode, int level) {
            return new UsSyncResult(stateCode, level, created, updated, skipped, failed, errors);
        }
    }
}
