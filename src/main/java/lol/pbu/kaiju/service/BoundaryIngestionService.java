package lol.pbu.kaiju.service;

import io.micronaut.context.annotation.Value;
import io.micronaut.data.jdbc.runtime.JdbcOperations;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.json.tree.JsonNode;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;
import lol.pbu.kaiju.dto.IngestBoundaryCommand;
import lol.pbu.kaiju.dto.IngestBoundaryResponse;
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
        WITH simplified AS (
            SELECT
                ST_Multi(ST_SimplifyPreserveTopology(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326), ?)) AS geom,
                ST_NPoints(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)) AS orig_pts
        )
        UPDATE administrative_regions r
        SET geom = s.geom
        FROM simplified s
        WHERE r.id = ?
          AND ST_IsValid(s.geom::geometry)
        RETURNING r.name, s.orig_pts, ST_NPoints(r.geom::geometry) AS simp_pts, ST_IsValid(r.geom::geometry) AS is_valid
    """;

    private final JdbcOperations jdbcOperations;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String allowedHost;
    private final String allowedPathPrefix;

    public BoundaryIngestionService(
            JdbcOperations jdbcOperations,
            ObjectMapper objectMapper,
            @Value("${kaiju.tigerweb.allowed-host:tigerweb.geo.census.gov}") String allowedHost,
            @Value("${kaiju.tigerweb.allowed-path-prefix:/arcgis/rest/services/TIGERweb/}") String allowedPathPrefix
    ) {
        this.jdbcOperations = jdbcOperations;
        this.objectMapper = objectMapper;
        this.allowedHost = allowedHost;
        this.allowedPathPrefix = allowedPathPrefix;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public BoundaryIngestionService(
            JdbcOperations jdbcOperations,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            String allowedHost,
            String allowedPathPrefix
    ) {
        this.jdbcOperations = jdbcOperations;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.allowedHost = allowedHost;
        this.allowedPathPrefix = allowedPathPrefix;
    }

    /**
     * Downloads boundary from authoritative Census Bureau endpoint, validates GeoJSON geometry,
     * simplifies geometry preserving topology, and updates administrative region in database.
     *
     * @param regionId the administrative region UUID
     * @param command  the ingest command containing source URL and optional tolerance
     * @return response containing region summary and reduction metrics
     */
    public IngestBoundaryResponse ingestBoundary(UUID regionId, IngestBoundaryCommand command) {
        if (regionId == null) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Region ID must not be null.");
        }
        if (command == null || command.sourceUrl() == null || command.sourceUrl().isBlank()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Source URL must not be blank.");
        }

        URI validatedUri = validateSourceUrl(command.sourceUrl());

        byte[] payload = fetchPayload(validatedUri);

        JsonNode geomNode = parseAndValidateGeoJson(payload);

        String geomJson;
        try {
            geomJson = objectMapper.writeValueAsString(geomNode);
        } catch (IOException e) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Failed to serialize geometry: " + e.getMessage());
        }

        double tolerance = resolveTolerance(command.tolerance());

        return updateRegionBoundary(regionId, geomJson, tolerance);
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
            if (lower.contains("<") || lower.contains(">") || lower.contains("%3c") || lower.contains("%3e")
                    || lower.contains("script") || lower.contains("javascript:") || lower.contains("onload")
                    || lower.contains("onerror") || lower.contains("..") || lower.contains("\\")) {
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

    private JsonNode parseAndValidateGeoJson(byte[] payload) {
        JsonNode root;
        try {
            root = objectMapper.readValue(payload, JsonNode.class);
        } catch (IOException e) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Response payload is not valid JSON: " + e.getMessage());
        }

        JsonNode geomNode = extractGeometryNode(root);
        validateGeometryNode(geomNode);
        return geomNode;
    }

    private JsonNode extractGeometryNode(JsonNode root) {
        JsonNode featuresNode = root.get("features");
        if (featuresNode != null) {
            if (!featuresNode.isArray() || featuresNode.size() == 0) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST, "GeoJSON FeatureCollection contains no features.");
            }
            JsonNode firstFeature = featuresNode.get(0);
            return firstFeature != null ? firstFeature.get(GEOMETRY_FIELD) : null;
        }
        if (root.get(GEOMETRY_FIELD) != null) {
            return root.get(GEOMETRY_FIELD);
        }
        return root;
    }

    private void validateGeometryNode(JsonNode geomNode) {
        if (geomNode == null || !geomNode.isObject()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "GeoJSON payload does not contain a valid geometry object.");
        }

        JsonNode typeNode = geomNode.get("type");
        if (typeNode == null || !typeNode.isString()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "GeoJSON geometry is missing a valid type field.");
        }

        String geomType = typeNode.getStringValue();
        if (!"Polygon".equalsIgnoreCase(geomType) && !"MultiPolygon".equalsIgnoreCase(geomType)) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Geometry type must be Polygon or MultiPolygon, found: " + geomType);
        }

        JsonNode coordsNode = geomNode.get("coordinates");
        if (coordsNode == null || !coordsNode.isArray() || coordsNode.size() == 0) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "GeoJSON geometry coordinates are missing or empty.");
        }

        validateCoordinatesTree(coordsNode);
    }

    private void validateCoordinatesTree(JsonNode node) {
        if (!node.isArray() || node.size() == 0) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Coordinate ring structure is empty or invalid.");
        }

        JsonNode firstChild = node.get(0);
        if (firstChild != null && firstChild.isNumber()) {
            validatePointCoordinates(node);
        } else if (firstChild != null && firstChild.isArray()) {
            for (JsonNode child : node.values()) {
                validateCoordinatesTree(child);
            }
        } else {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Unexpected coordinate structure in GeoJSON geometry.");
        }
    }

    private void validatePointCoordinates(JsonNode node) {
        if (node.size() < 2 || node.get(1) == null || !node.get(1).isNumber()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Coordinate point must contain at least two numeric values.");
        }
        double lon = node.get(0).getNumberValue().doubleValue();
        double lat = node.get(1).getNumberValue().doubleValue();

        if (Double.isNaN(lon) || Double.isNaN(lat) || Double.isInfinite(lon) || Double.isInfinite(lat)) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Coordinates must contain valid finite numbers.");
        }

        if (lon < -180.0 || lon > 180.0 || lat < -90.0 || lat > 90.0) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                    String.format(Locale.ROOT, "Coordinates out of valid WGS84 bounds (-180..180 lon, -90..90 lat): lon=%.6f, lat=%.6f", lon, lat));
        }
    }

    double resolveTolerance(Double requestedTolerance) {
        double tolerance = requestedTolerance != null ? requestedTolerance : DEFAULT_TOLERANCE;
        return Math.clamp(tolerance, MIN_TOLERANCE, MAX_TOLERANCE);
    }

    private IngestBoundaryResponse updateRegionBoundary(UUID regionId, String geomJson, double tolerance) {
        return jdbcOperations.execute(connection -> {
            try (PreparedStatement updateStmt = connection.prepareStatement(UPDATE_SQL)) {
                updateStmt.setString(1, geomJson);
                updateStmt.setDouble(2, tolerance);
                updateStmt.setString(3, geomJson);
                updateStmt.setObject(4, regionId);

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
                throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error updating administrative region boundary: " + e.getMessage());
            }

            // Zero rows returned: determine if region is missing or geometry is invalid
            boolean regionExists;
            try (PreparedStatement checkStmt = connection.prepareStatement("SELECT 1 FROM administrative_regions WHERE id = ?")) {
                checkStmt.setObject(1, regionId);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    regionExists = rs.next();
                }
            } catch (SQLException e) {
                throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error verifying administrative region existence: " + e.getMessage());
            }

            if (!regionExists) {
                throw new HttpStatusException(HttpStatus.NOT_FOUND, "Administrative region not found");
            }

            throw new HttpStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Resulting geometry is topologically invalid");
        });
    }
}
