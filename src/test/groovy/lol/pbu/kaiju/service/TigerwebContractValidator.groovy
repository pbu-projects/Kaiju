package lol.pbu.kaiju.service

import io.micronaut.serde.ObjectMapper
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.List
import java.util.Map
import java.util.Optional

class TigerwebContractValidator {

    private static final List<String> MANDATORY_CENSUS_PROPERTIES = ["BASENAME", "GEOID", "STATE"]

    private final ObjectMapper objectMapper

    TigerwebContractValidator() {
        this(ObjectMapper.getDefault())
    }

    TigerwebContractValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper
    }

    void validateRequest(HttpRequest request) {
        if (request == null) {
            throw new ContractValidationException("HttpRequest must not be null")
        }

        String method = request.method()
        if (!"GET".equalsIgnoreCase(method)) {
            throw new ContractValidationException("Expected HTTP GET method for Census TIGERweb query, but received: " + method)
        }

        URI uri = request.uri()
        if (uri == null) {
            throw new ContractValidationException("Request URI must not be null")
        }

        String scheme = uri.scheme
        if (!"https".equalsIgnoreCase(scheme)) {
            throw new ContractValidationException("Expected HTTPS scheme for Census TIGERweb, but received: " + scheme)
        }

        String host = uri.host
        if (host == null || !host.toLowerCase().contains("census.gov")) {
            throw new ContractValidationException("Expected census.gov host for Census TIGERweb, but received: " + host)
        }

        String path = uri.path
        if (path == null || !path.startsWith("/arcgis/rest/services/TIGERweb/")) {
            throw new ContractValidationException("Expected path starting with /arcgis/rest/services/TIGERweb/, but received: " + path)
        }

        String rawQuery = uri.rawQuery
        if (rawQuery == null || (!rawQuery.contains("f=geojson") && !rawQuery.contains("f=json"))) {
            throw new ContractValidationException("Census TIGERweb request must include query parameter f=geojson or f=json")
        }

        HttpHeaders headers = request.headers()
        if (headers != null) {
            Optional<String> accept = headers.firstValue("Accept")
            if (accept.isEmpty() || (!accept.get().contains("application/json") && !accept.get().contains("application/geo+json"))) {
                throw new ContractValidationException("Accept header must include application/json or application/geo+json, found: " + accept.orElse(null))
            }

            Optional<String> userAgent = headers.firstValue("User-Agent")
            if (userAgent.isEmpty() || userAgent.get().trim().isEmpty()) {
                throw new ContractValidationException("User-Agent header must be provided for Census TIGERweb requests")
            }
        }
    }

    <T> HttpResponse<T> validateResponse(HttpResponse<T> response) {
        if (response == null) {
            throw new ContractValidationException("HttpResponse must not be null")
        }

        if (response.statusCode() == 200) {
            T body = response.body()
            if (body == null) {
                throw new SchemaDriftException("Response body must not be null for successful Census TIGERweb response")
            }

            if (body instanceof InputStream) {
                byte[] bytes = ((InputStream) body).readAllBytes()
                validateResponseSchema(bytes)
                return (HttpResponse<T>) new StubHttpResponse<InputStream>(response.statusCode(), new ByteArrayInputStream(bytes))
            } else if (body instanceof byte[]) {
                validateResponseSchema((byte[]) body)
                return response
            } else if (body instanceof String) {
                validateResponseSchema(((String) body).getBytes(StandardCharsets.UTF_8))
                return response
            } else {
                throw new SchemaDriftException("Unsupported response body type for schema validation: " + body.getClass().name)
            }
        }

        return response
    }

    void validateResponseSchema(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new SchemaDriftException("Response payload must not be empty")
        }

        Object parsed
        try {
            parsed = objectMapper.readValue(payload, Map.class)
        } catch (Exception e) {
            throw new SchemaDriftException("Response payload is not valid JSON: " + e.message, e)
        }

        validateParsedGeoJson(parsed)
    }

    void validateResponseSchema(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new SchemaDriftException("Response payload must not be empty")
        }
        validateResponseSchema(json.getBytes(StandardCharsets.UTF_8))
    }

    void validateResponseSchema(InputStream inputStream) {
        if (inputStream == null) {
            throw new SchemaDriftException("InputStream must not be null")
        }
        byte[] bytes = inputStream.readAllBytes()
        validateResponseSchema(bytes)
    }

    private void validateParsedGeoJson(Object root) {
        if (!(root instanceof Map)) {
            throw new SchemaDriftException("Census TIGERweb response must be a JSON object, found: " + (root?.getClass()?.simpleName))
        }

        Map<?, ?> map = (Map<?, ?>) root
        Object type = map.get("type")
        if (!"FeatureCollection".equals(type)) {
            throw new SchemaDriftException("Expected GeoJSON type 'FeatureCollection', found: '" + type + "'")
        }

        Object featuresObj = map.get("features")
        if (!(featuresObj instanceof List)) {
            throw new SchemaDriftException("Census TIGERweb FeatureCollection must contain a 'features' array")
        }

        List<?> features = (List<?>) featuresObj
        if (features.isEmpty()) {
            throw new SchemaDriftException("Census TIGERweb FeatureCollection contains no features")
        }

        for (Object featureObj : features) {
            validateFeature(featureObj)
        }
    }

    private void validateFeature(Object featureObj) {
        if (!(featureObj instanceof Map)) {
            throw new SchemaDriftException("Each item in features array must be a JSON object")
        }

        Map<?, ?> feature = (Map<?, ?>) featureObj
        Object type = feature.get("type")
        if (!"Feature".equals(type)) {
            throw new SchemaDriftException("Expected feature type 'Feature', found: '" + type + "'")
        }

        Object geomObj = feature.get("geometry")
        if (geomObj == null || !(geomObj instanceof Map)) {
            throw new SchemaDriftException("Feature must contain a non-null geometry object")
        }

        Map<?, ?> geom = (Map<?, ?>) geomObj
        Object geomType = geom.get("type")
        if (!"Polygon".equals(geomType) && !"MultiPolygon".equals(geomType)) {
            throw new SchemaDriftException("Authoritative boundary geometry type must be Polygon or MultiPolygon, found: '" + geomType + "'")
        }

        Object coordsObj = geom.get("coordinates")
        if (!(coordsObj instanceof List) || ((List<?>) coordsObj).isEmpty()) {
            throw new SchemaDriftException("Geometry coordinates must be a non-empty array")
        }

        validateCoordinates((String) geomType, (List<?>) coordsObj)

        Object propsObj = feature.get("properties")
        if (propsObj == null || !(propsObj instanceof Map)) {
            throw new SchemaDriftException("Feature must contain a properties object")
        }

        Map<?, ?> props = (Map<?, ?>) propsObj
        for (String requiredProp : MANDATORY_CENSUS_PROPERTIES) {
            if (!props.containsKey(requiredProp) || props.get(requiredProp) == null || props.get(requiredProp).toString().trim().isEmpty()) {
                throw new SchemaDriftException("Census TIGERweb schema drift detected: missing required property '" + requiredProp + "' in feature properties")
            }
        }
    }

    private void validateCoordinates(String geomType, List<?> coordinates) {
        if ("Polygon".equals(geomType)) {
            for (Object ringObj : coordinates) {
                validateLinearRing(ringObj)
            }
        } else if ("MultiPolygon".equals(geomType)) {
            for (Object polyObj : coordinates) {
                if (!(polyObj instanceof List)) {
                    throw new SchemaDriftException("MultiPolygon coordinates must contain polygon arrays")
                }
                for (Object ringObj : (List<?>) polyObj) {
                    validateLinearRing(ringObj)
                }
            }
        }
    }

    private void validateLinearRing(Object ringObj) {
        if (!(ringObj instanceof List)) {
            throw new SchemaDriftException("Linear ring must be an array of coordinates")
        }

        List<?> ring = (List<?>) ringObj
        if (ring.size() < 4) {
            throw new SchemaDriftException("Linear ring must contain at least 4 coordinates, found: " + ring.size())
        }

        for (Object ptObj : ring) {
            if (!(ptObj instanceof List)) {
                throw new SchemaDriftException("Coordinate point must be an array [lon, lat]")
            }
            List<?> pt = (List<?>) ptObj
            if (pt.size() < 2) {
                throw new SchemaDriftException("Coordinate point must contain at least 2 numbers, found: " + pt.size())
            }
            if (!(pt.get(0) instanceof Number) || !(pt.get(1) instanceof Number)) {
                throw new SchemaDriftException("Coordinate values must be numbers, found: " + pt)
            }
            double lon = ((Number) pt.get(0)).doubleValue()
            double lat = ((Number) pt.get(1)).doubleValue()
            if (Double.isNaN(lon) || Double.isNaN(lat) || Double.isInfinite(lon) || Double.isInfinite(lat)) {
                throw new SchemaDriftException("Coordinate values must be finite numbers")
            }
            if (lon < -180.0d || lon > 180.0d || lat < -90.0d || lat > 90.0d) {
                throw new SchemaDriftException("Coordinate out of WGS84 bounds: lon=" + lon + ", lat=" + lat)
            }
        }

        List<?> firstPt = (List<?>) ring.first()
        List<?> lastPt = (List<?>) ring.last()
        double firstLon = ((Number) firstPt.get(0)).doubleValue()
        double firstLat = ((Number) firstPt.get(1)).doubleValue()
        double lastLon = ((Number) lastPt.get(0)).doubleValue()
        double lastLat = ((Number) lastPt.get(1)).doubleValue()

        if (Double.compare(firstLon, lastLon) != 0 || Double.compare(firstLat, lastLat) != 0) {
            throw new SchemaDriftException("Linear ring must be closed: first coordinate [" + firstLon + ", " + firstLat + "] does not match last coordinate [" + lastLon + ", " + lastLat + "]")
        }
    }
}
