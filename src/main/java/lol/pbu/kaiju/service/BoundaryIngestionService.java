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
import lol.pbu.kaiju.dto.IngestBoundaryCommand;
import lol.pbu.kaiju.dto.IngestBoundaryResponse;
import lol.pbu.kaiju.serde.JtsGeometrySerde;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * Service for downloading, validating, and ingesting administrative boundaries
 * directly from authoritative US Census Bureau TIGERweb endpoints into PostGIS.
 */
@Singleton
public class BoundaryIngestionService {

    private static final Logger LOG = LoggerFactory.getLogger(BoundaryIngestionService.class);

    public static final double DEFAULT_TOLERANCE = 0.001;
    public static final double MIN_TOLERANCE = 0.0001;
    public static final double MAX_TOLERANCE = 0.05;
    public static final int MAX_PAYLOAD_BYTES = 15 * 1024 * 1024; // 15MB

    private static final String GEOMETRY_FIELD = "geometry";
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

    private final AdministrativeRegionService administrativeRegionService;
    private final JdbcOperations jdbcOperations;
    private final ObjectMapper objectMapper;
    private final JtsGeometrySerde jtsGeometrySerde;
    private final HttpClient httpClient;
    private final String allowedHost;
    private final String allowedPathPrefix;

    @Inject
    public BoundaryIngestionService(
            @Nullable AdministrativeRegionService administrativeRegionService,
            @Nullable JdbcOperations jdbcOperations,
            ObjectMapper objectMapper,
            JtsGeometrySerde jtsGeometrySerde,
            @Nullable HttpClient httpClient,
            @Value("${kaiju.tigerweb.allowed-host:tigerweb.geo.census.gov}") String allowedHost,
            @Value("${kaiju.tigerweb.allowed-path-prefix:/arcgis/rest/services/TIGERweb/}") String allowedPathPrefix
    ) {
        this.administrativeRegionService = administrativeRegionService;
        this.jdbcOperations = jdbcOperations;
        this.objectMapper = objectMapper;
        this.jtsGeometrySerde = jtsGeometrySerde;
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.allowedHost = allowedHost;
        this.allowedPathPrefix = allowedPathPrefix;
    }

    public BoundaryIngestionService(
            AdministrativeRegionService administrativeRegionService,
            JdbcOperations jdbcOperations,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            String allowedHost,
            String allowedPathPrefix
    ) {
        this(administrativeRegionService, jdbcOperations, objectMapper, new JtsGeometrySerde(), httpClient, allowedHost, allowedPathPrefix);
    }

    public BoundaryIngestionService(
            JdbcOperations jdbcOperations,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            String allowedHost,
            String allowedPathPrefix
    ) {
        this(null, jdbcOperations, objectMapper, new JtsGeometrySerde(), httpClient, allowedHost, allowedPathPrefix);
    }

    public BoundaryIngestionService(
            JdbcOperations jdbcOperations,
            ObjectMapper objectMapper,
            JtsGeometrySerde jtsGeometrySerde,
            @Nullable HttpClient httpClient,
            String allowedHost,
            String allowedPathPrefix
    ) {
        this(null, jdbcOperations, objectMapper, jtsGeometrySerde, httpClient, allowedHost, allowedPathPrefix);
    }

    public JdbcOperations getJdbcOperations() {
        return jdbcOperations;
    }

    public AdministrativeRegionService getAdministrativeRegionService() {
        return administrativeRegionService;
    }

    /**
     * Downloads boundary from authoritative Census Bureau endpoint, validates GeoJSON geometry,
     * simplifies geometry preserving topology, and updates administrative region in database.
     *
     * @param regionId    the administrative region UUID
     * @param command     the ingest command containing source URL and optional tolerance
     * @param actorUserId the user initiating boundary ingestion
     * @return response containing region summary and reduction metrics
     */
    public IngestBoundaryResponse ingestBoundary(UUID regionId, IngestBoundaryCommand command, @Nullable UUID actorUserId) {
        if (regionId == null) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Region ID must not be null.");
        }
        if (command == null || command.sourceUrl() == null || command.sourceUrl().isBlank()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Source URL must not be blank.");
        }

        double tolerance = resolveTolerance(command.tolerance());

        URI validatedUri = validateSourceUrl(command.sourceUrl());

        byte[] payload = fetchPayload(validatedUri);

        JsonNode geomNode = extractGeometryNodeFromPayload(payload);

        validateAndDeserializeGeometry(geomNode);

        String geomJson;
        try {
            geomJson = objectMapper.writeValueAsString(geomNode);
        } catch (IOException e) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Failed to serialize geometry: " + e.getMessage());
        }

        if (administrativeRegionService != null && actorUserId != null) {
            return administrativeRegionService.applyIngestedBoundary(regionId, geomJson, tolerance, command.sourceUrl(), actorUserId);
        }

        return updateRegionBoundary(regionId, geomJson, tolerance);
    }

    public IngestBoundaryResponse ingestBoundary(UUID regionId, IngestBoundaryCommand command) {
        return ingestBoundary(regionId, command, null);
    }

    private URI validateSourceUrl(String sourceUrl) {
        URI uri;
        try {
            uri = new URI(sourceUrl.trim());
        } catch (URISyntaxException e) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Invalid source URL syntax: " + e.getMessage());
        }

        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Source URL must use the https scheme.");
        }

        String host = uri.getHost();
        if (host == null || !host.equalsIgnoreCase(allowedHost)) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Host must strictly be " + allowedHost);
        }

        int port = uri.getPort();
        if (port != -1 && port != 443) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Port must be default or 443.");
        }

        String path = uri.getPath();
        if (path == null || !path.startsWith(allowedPathPrefix)) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Path must strictly start with " + allowedPathPrefix);
        }

        if (path.contains("..") || path.contains("<") || path.contains(">")) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Path contains invalid characters or traversal sequences.");
        }

        String query = uri.getRawQuery();
        if (query != null) {
            String lower = query.toLowerCase(Locale.ROOT);
            if (lower.contains("<script") || lower.contains("%3cscript") || lower.contains("javascript:")
                    || lower.contains("onload=") || lower.contains("onerror=") || lower.contains("..")
                    || lower.contains("\\")) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Query parameters contain disallowed script or traversal characters.");
            }
        }

        return uri;
    }

    private byte[] fetchPayload(URI uri) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json, application/geo+json")
                .header("User-Agent", "Kaiju-GIS-Ingestion/1.0")
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new HttpStatusException(HttpStatus.BAD_GATEWAY, "Failed to connect to authoritative boundary service: " + e.getMessage());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Boundary download request was interrupted.");
        }

        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new HttpStatusException(HttpStatus.BAD_GATEWAY, "Authoritative boundary service returned HTTP status " + response.statusCode());
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int totalBytes = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                totalBytes += read;
                if (totalBytes > MAX_PAYLOAD_BYTES) {
                    throw new HttpStatusException(HttpStatus.REQUEST_ENTITY_TOO_LARGE, "Response payload exceeds maximum allowed size of 15MB.");
                }
                baos.write(buffer, 0, read);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new HttpStatusException(HttpStatus.BAD_GATEWAY, "Failed reading boundary response payload: " + e.getMessage());
        }
    }

    private JsonNode extractGeometryNodeFromPayload(byte[] payload) {
        JsonNode root;
        try {
            root = objectMapper.readValue(payload, JsonNode.class);
        } catch (IOException e) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Response payload is not valid JSON: " + e.getMessage());
        }

        if (root == null || !root.isObject()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "GeoJSON payload must be a JSON object.");
        }

        JsonNode featuresNode = root.get("features");
        if (featuresNode != null) {
            return extractFeatureGeometry(featuresNode);
        }

        if (root.get(GEOMETRY_FIELD) != null) {
            return root.get(GEOMETRY_FIELD);
        }

        return root;
    }

    private JsonNode extractFeatureGeometry(JsonNode featuresNode) {
        if (!featuresNode.isArray() || featuresNode.size() == 0) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "GeoJSON FeatureCollection contains no features.");
        }
        if (featuresNode.size() > 1) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "GeoJSON FeatureCollection contains multiple features. A single region feature was expected.");
        }
        JsonNode firstFeature = featuresNode.get(0);
        if (firstFeature == null || !firstFeature.isObject()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Feature must be a JSON object.");
        }
        JsonNode geomNode = firstFeature.get(GEOMETRY_FIELD);
        if (geomNode == null || geomNode.isNull()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "GeoJSON payload does not contain a valid geometry object.");
        }
        return geomNode;
    }

    private Geometry validateAndDeserializeGeometry(JsonNode geomNode) {
        if (geomNode == null || !geomNode.isObject()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "GeoJSON payload does not contain a valid geometry object.");
        }

        validateRingsClosed(geomNode);

        Geometry geometry;
        try {
            geometry = jtsGeometrySerde.deserializeFromNode(geomNode);
        } catch (Exception e) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Failed to deserialize GeoJSON geometry: " + e.getMessage());
        }

        if (geometry == null) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "GeoJSON payload does not contain a valid geometry object.");
        }

        if (!(geometry instanceof Polygon || geometry instanceof MultiPolygon)) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Geometry type must be Polygon or MultiPolygon, found: " + geometry.getGeometryType());
        }

        if (!geometry.isValid()) {
            throw new HttpStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Resulting geometry is topologically invalid");
        }

        geometry.apply((CoordinateFilter) coord -> {
            if (Double.isNaN(coord.getX()) || Double.isNaN(coord.getY()) || Double.isInfinite(coord.getX()) || Double.isInfinite(coord.getY())) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Coordinates must contain valid finite numbers.");
            }
            if (coord.getX() < -180.0 || coord.getX() > 180.0 || coord.getY() < -90.0 || coord.getY() > 90.0) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                        String.format(Locale.ROOT, "Coordinates out of valid WGS84 bounds (-180..180 lon, -90..90 lat): lon=%.6f, lat=%.6f", coord.getX(), coord.getY()));
            }
        });

        return geometry;
    }

    private void validateRingsClosed(JsonNode geomNode) {
        JsonNode typeNode = geomNode.get("type");
        if (typeNode == null || !typeNode.isString()) {
            return;
        }
        String type = typeNode.getStringValue();
        JsonNode coords = geomNode.get("coordinates");
        if (coords == null || !coords.isArray()) {
            return;
        }
        if ("Polygon".equalsIgnoreCase(type)) {
            validatePolygonRings(coords);
        } else if ("MultiPolygon".equalsIgnoreCase(type)) {
            validateMultiPolygonRings(coords);
        }
    }

    private void validatePolygonRings(JsonNode coords) {
        for (JsonNode ring : coords.values()) {
            checkRingClosure(ring);
        }
    }

    private void validateMultiPolygonRings(JsonNode coords) {
        for (JsonNode poly : coords.values()) {
            if (poly.isArray()) {
                for (JsonNode ring : poly.values()) {
                    checkRingClosure(ring);
                }
            }
        }
    }

    private void checkRingClosure(JsonNode ring) {
        if (ring == null || !ring.isArray() || ring.size() < 4) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Linear ring must contain at least 4 coordinate positions.");
        }
        JsonNode first = ring.get(0);
        JsonNode last = ring.get(ring.size() - 1);
        if (first != null && last != null && first.isArray() && last.isArray() && first.size() >= 2 && last.size() >= 2) {
            JsonNode firstLon = first.get(0);
            JsonNode firstLat = first.get(1);
            JsonNode lastLon = last.get(0);
            JsonNode lastLat = last.get(1);
            if (firstLon.isNumber() && firstLat.isNumber() && lastLon.isNumber() && lastLat.isNumber()
                    && (Double.compare(firstLon.getDoubleValue(), lastLon.getDoubleValue()) != 0
                    || Double.compare(firstLat.getDoubleValue(), lastLat.getDoubleValue()) != 0)) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Linear ring must be closed (first and last coordinate points must match).");
            }
        }
    }

    double resolveTolerance(Double requestedTolerance) {
        if (requestedTolerance == null) {
            return DEFAULT_TOLERANCE;
        }
        if (Double.isNaN(requestedTolerance) || Double.isInfinite(requestedTolerance)) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Tolerance must be a finite, positive number.");
        }
        if (requestedTolerance <= 0.0) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Tolerance must be greater than zero.");
        }
        return Math.clamp(requestedTolerance, MIN_TOLERANCE, MAX_TOLERANCE);
    }

    private IngestBoundaryResponse updateRegionBoundary(UUID regionId, String geomJson, double tolerance) {
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

            // Zero rows returned: determine if region is missing or geometry is invalid
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
}
