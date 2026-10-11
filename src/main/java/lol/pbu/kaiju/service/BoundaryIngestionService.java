package lol.pbu.kaiju.service;

import io.micronaut.context.annotation.Value;
import io.micronaut.data.jdbc.runtime.JdbcOperations;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Singleton;
import lol.pbu.kaiju.dto.IngestBoundaryCommand;
import lol.pbu.kaiju.dto.IngestBoundaryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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

    private final JdbcOperations jdbcOperations;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String allowedHost;
    private final String allowedPathPrefix;

    public BoundaryIngestionService(
            JdbcOperations jdbcOperations,
            @Value("${kaiju.tigerweb.allowed-host:tigerweb.geo.census.gov}") String allowedHost,
            @Value("${kaiju.tigerweb.allowed-path-prefix:/arcgis/rest/services/TIGERweb/}") String allowedPathPrefix
    ) {
        this.jdbcOperations = jdbcOperations;
        this.objectMapper = new ObjectMapper();
        this.allowedHost = allowedHost;
        this.allowedPathPrefix = allowedPathPrefix;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public BoundaryIngestionService(
            JdbcOperations jdbcOperations,
            HttpClient httpClient,
            String allowedHost,
            String allowedPathPrefix
    ) {
        this.jdbcOperations = jdbcOperations;
        this.objectMapper = new ObjectMapper();
        this.httpClient = httpClient;
        this.allowedHost = allowedHost;
        this.allowedPathPrefix = allowedPathPrefix;
    }

    /**
     * Ingests, simplifies, and validates a geographic boundary for an administrative region.
     *
     * @param regionId the identifier of the administrative region to update
     * @param command  the ingestion command containing source URL and optional tolerance
     * @return the ingestion response summary
     */
    public IngestBoundaryResponse ingestBoundary(UUID regionId, IngestBoundaryCommand command) {
        if (regionId == null) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Region ID is required.");
        }
        if (command == null || command.sourceUrl() == null || command.sourceUrl().isBlank()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Source URL is required.");
        }

        URI validatedUri = validateSourceUrl(command.sourceUrl());
        byte[] payload = fetchPayload(validatedUri);
        JsonNode geomNode = parseAndValidateGeoJson(payload);

        String geomJson;
        try {
            geomJson = objectMapper.writeValueAsString(geomNode);
        } catch (JacksonException e) {
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
                .header("User-Agent", "Kaiju-Boundary-Ingestion/1.0")
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new HttpStatusException(HttpStatus.BAD_GATEWAY, "Failed to connect to authoritative boundary service: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Boundary download request was interrupted.");
        }

        if (response.statusCode() != 200) {
            throw new HttpStatusException(HttpStatus.BAD_GATEWAY, "Authoritative boundary service returned HTTP status " + response.statusCode());
        }

        try (InputStream in = response.body()) {
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
            root = objectMapper.readTree(payload);
        } catch (JacksonException e) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Response payload is not valid JSON: " + e.getMessage());
        }

        JsonNode geomNode;
        if (root.has("features")) {
            JsonNode features = root.get("features");
            if (!features.isArray() || features.isEmpty()) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST, "GeoJSON FeatureCollection contains no features.");
            }
            JsonNode firstFeature = features.get(0);
            geomNode = firstFeature.get("geometry");
        } else if (root.has("geometry")) {
            geomNode = root.get("geometry");
        } else {
            geomNode = root;
        }

        if (geomNode == null || !geomNode.isObject()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "GeoJSON payload does not contain a valid geometry object.");
        }

        JsonNode typeNode = geomNode.get("type");
        if (typeNode == null || !typeNode.isTextual()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "GeoJSON geometry is missing a valid type field.");
        }

        String geomType = typeNode.asText();
        if (!"Polygon".equalsIgnoreCase(geomType) && !"MultiPolygon".equalsIgnoreCase(geomType)) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Geometry type must be Polygon or MultiPolygon, found: " + geomType);
        }

        JsonNode coordsNode = geomNode.get("coordinates");
        if (coordsNode == null || !coordsNode.isArray() || coordsNode.isEmpty()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "GeoJSON geometry coordinates are missing or empty.");
        }

        validateCoordinatesTree(coordsNode);

        return geomNode;
    }

    private void validateCoordinatesTree(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Coordinate ring structure is empty or invalid.");
        }

        JsonNode firstChild = node.get(0);
        if (firstChild.isNumber()) {
            if (node.size() < 2 || !node.get(1).isNumber()) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Coordinate point must contain at least two numeric values.");
            }
            double lon = node.get(0).asDouble();
            double lat = node.get(1).asDouble();

            if (Double.isNaN(lon) || Double.isNaN(lat) || Double.isInfinite(lon) || Double.isInfinite(lat)) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Coordinates must contain valid finite numbers.");
            }

            if (lon < -180.0 || lon > 180.0 || lat < -90.0 || lat > 90.0) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                        String.format(Locale.ROOT, "Coordinates out of valid WGS84 bounds (-180..180 lon, -90..90 lat): lon=%.6f, lat=%.6f", lon, lat));
            }
        } else if (firstChild.isArray()) {
            for (JsonNode child : node) {
                validateCoordinatesTree(child);
            }
        } else {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Unexpected coordinate structure in GeoJSON geometry.");
        }
    }

    private double resolveTolerance(Double requestedTolerance) {
        double tolerance = requestedTolerance != null ? requestedTolerance : DEFAULT_TOLERANCE;
        return Math.max(MIN_TOLERANCE, Math.min(MAX_TOLERANCE, tolerance));
    }

    private IngestBoundaryResponse updateRegionBoundary(UUID regionId, String geomJson, double tolerance) {
        return jdbcOperations.execute(connection -> {
            String regionName;
            try (PreparedStatement checkStmt = connection.prepareStatement("SELECT name FROM administrative_regions WHERE id = ?")) {
                checkStmt.setObject(1, regionId);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (!rs.next()) {
                        throw new HttpStatusException(HttpStatus.NOT_FOUND, "Administrative region not found");
                    }
                    regionName = rs.getString("name");
                }
            }

            String updateSql = """
                UPDATE administrative_regions
                SET geom = ST_Multi(ST_SimplifyPreserveTopology(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326), ?))
                WHERE id = ?
            """;

            try (PreparedStatement updateStmt = connection.prepareStatement(updateSql)) {
                updateStmt.setString(1, geomJson);
                updateStmt.setDouble(2, tolerance);
                updateStmt.setObject(3, regionId);
                int updatedRows = updateStmt.executeUpdate();
                if (updatedRows == 0) {
                    throw new HttpStatusException(HttpStatus.NOT_FOUND, "Administrative region not found");
                }
            }

            String statsSql = """
                SELECT
                    ST_NPoints(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)) AS original_points,
                    ST_NPoints(geom::geometry) AS simplified_points,
                    ST_IsValid(geom::geometry) AS is_valid
                FROM administrative_regions
                WHERE id = ?
            """;

            try (PreparedStatement statsStmt = connection.prepareStatement(statsSql)) {
                statsStmt.setString(1, geomJson);
                statsStmt.setObject(2, regionId);
                try (ResultSet rs = statsStmt.executeQuery()) {
                    if (rs.next()) {
                        int origPoints = rs.getInt("original_points");
                        int simpPoints = rs.getInt("simplified_points");
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
                    } else {
                        throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to compute boundary statistics.");
                    }
                }
            } catch (SQLException e) {
                LOG.error("Failed calculating boundary statistics for region {}: {}", regionId, e.getMessage(), e);
                throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error computing boundary simplification statistics: " + e.getMessage());
            }
        });
    }
}
