package lol.pbu.kaiju.util

import io.micronaut.core.convert.ConversionContext
import io.micronaut.core.type.Argument
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Pageable
import io.micronaut.data.model.Sort
import io.micronaut.json.tree.JsonNode
import io.micronaut.serde.Decoder
import io.micronaut.serde.Deserializer
import io.micronaut.serde.ObjectMapper
import io.micronaut.serde.exceptions.SerdeException
import io.micronaut.validation.validator.Validator as MicronautValidator
import jakarta.validation.Validator
import lol.pbu.kaiju.dto.Coordinates
import lol.pbu.kaiju.dto.CreateOrganizationAuditLogRequest
import lol.pbu.kaiju.dto.CreateOrganizationRequest
import lol.pbu.kaiju.dto.CreateProjectAuditLogRequest
import lol.pbu.kaiju.dto.CreateShiftRequest
import lol.pbu.kaiju.dto.IngestBoundaryRequest
import lol.pbu.kaiju.dto.ShiftTimeRange
import lol.pbu.kaiju.dto.UpdateOrganizationAuditLogRequest
import lol.pbu.kaiju.dto.UpdateOrganizationRequest
import lol.pbu.kaiju.dto.UpdateProjectAuditLogRequest
import lol.pbu.kaiju.dto.UpdateShiftRequest
import lol.pbu.kaiju.dto.ValidShiftLocationValidator
import lol.pbu.kaiju.dto.ValidTimeRangeValidator
import lol.pbu.kaiju.model.AuditAction
import lol.pbu.kaiju.model.JtsPointConverter
import lol.pbu.kaiju.model.JtsPolygonConverter
import lol.pbu.kaiju.serde.JtsGeometrySerde
import lol.pbu.kaiju.service.AdministrativeRegionService
import lol.pbu.kaiju.service.BoundaryIngestionService
import lol.pbu.kaiju.service.StubHttpClient
import lol.pbu.kaiju.service.StubHttpResponse
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel
import org.postgresql.util.PGobject
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.time.ZoneOffset

class SpatialAndInputValidationSpec extends Specification {

    @Shared
    GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326)

    @Shared
    Validator validator = MicronautValidator.getInstance()

    @Shared
    ObjectMapper objectMapper = ObjectMapper.getDefault()

    SpatialMappingService spatialMappingService = new SpatialMappingService(geometryFactory)
    JtsGeometrySerde geometrySerde = new JtsGeometrySerde(geometryFactory)
    JtsPointConverter pointConverter = new JtsPointConverter()
    JtsPolygonConverter polygonConverter = new JtsPolygonConverter()

    // --- PageRequests tests ---

    def "null pageable defaults to size 20 and sorts by given field"() {
        when:
        CursoredPageable resolved = PageRequests.resolvePageable(null, "name")

        then:
        verifyAll(resolved) {
            size == PageRequests.DEFAULT_PAGE_SIZE
            size == 20
            sort.orderBy.size() == 1
            sort.orderBy[0].property == "name"
            sort.orderBy[0].direction == Sort.Order.Direction.ASC
        }
    }

    def "oversized pageable is capped at 100 while keeping page, cursor, and sort"() {
        given:
        Pageable.Cursor cursor = Pageable.Cursor.of("next-page-token")
        Sort customSort = Sort.of(Sort.Order.desc("createdAt"))
        CursoredPageable oversized = CursoredPageable.from(
                3,
                cursor,
                Pageable.Mode.CURSOR_NEXT,
                500,
                customSort,
                true
        )

        when:
        CursoredPageable resolved = PageRequests.resolvePageable(oversized, "name")

        then:
        verifyAll(resolved) {
            size == PageRequests.MAX_PAGE_SIZE
            size == 100
            number == 3
            mode == Pageable.Mode.CURSOR_NEXT
            requestTotal()
            resolved.cursor().isPresent()
            resolved.cursor().get() == cursor
            sort == customSort
        }
    }

    def "pageable with valid size 25 is kept as 25"() {
        given:
        CursoredPageable requested = CursoredPageable.from(
                1,
                null,
                Pageable.Mode.CURSOR_NEXT,
                25,
                Sort.of(Sort.Order.asc("id")),
                false
        )

        when:
        CursoredPageable resolved = PageRequests.resolvePageable(requested, "name")

        then:
        verifyAll(resolved) {
            size == 25
            number == 1
            sort.orderBy[0].property == "id"
        }
    }

    // --- JtsGeometrySerde tests ---

    @Unroll
    def "geometry serde rejects NaN and Infinity numbers in coordinates (#lon, #lat)"() {
        given:
        JsonNode pointNode = JsonNode.createObjectNode([
                type       : JsonNode.createStringNode("Point"),
                coordinates: JsonNode.createArrayNode([
                        JsonNode.createNumberNode(lon),
                        JsonNode.createNumberNode(lat)
                ])
        ])

        when:
        geometrySerde.deserializeFromNode(pointNode)

        then:
        def ex = thrown(SerdeException)
        ex.message.contains("finite")

        where:
        lon                      | lat
        Double.NaN               | 39.0d
        -105.0d                  | Double.NaN
        Double.POSITIVE_INFINITY | 39.0d
        -105.0d                  | Double.NEGATIVE_INFINITY
    }

    @Unroll
    def "geometry serde rejects coordinates outside WGS84 bounds (#lon, #lat)"() {
        given:
        JsonNode pointNode = JsonNode.createObjectNode([
                type       : JsonNode.createStringNode("Point"),
                coordinates: JsonNode.createArrayNode([
                        JsonNode.createNumberNode(lon),
                        JsonNode.createNumberNode(lat)
                ])
        ])
        Decoder decoder = Mock(Decoder) {
            decodeNode() >> pointNode
        }
        Deserializer.DecoderContext context = Mock(Deserializer.DecoderContext)

        when:
        geometrySerde.deserialize(decoder, context, Argument.of(Geometry))

        then:
        def ex = thrown(SerdeException)
        ex.message.contains("WGS84 bounds")

        where:
        lon     | lat
        181.0d  | 39.0d
        -181.0d | 39.0d
        -105.0d | -91.0d
        -105.0d | 91.0d
    }

    @Unroll
    def "geometry serde rejects null elements inside #jsonPayload"() {
        given:
        JsonNode node = objectMapper.readValue(jsonPayload, JsonNode)

        when:
        geometrySerde.deserializeFromNode(node)

        then:
        thrown(SerdeException)

        where:
        jsonPayload << [
                '{"type":"Point","coordinates":[null,39.0]}',
                '{"type":"Point","coordinates":[-105.0,null]}',
                '{"type":"Polygon","coordinates":[null]}',
                '{"type":"Polygon","coordinates":[[[-105.0,39.0],null,[-104.0,40.0],[-105.0,39.0]]]}'
        ]
    }

    def "geometry serde rejects polygons with more than 10000 vertices"() {
        given:
        List<JsonNode> ringPoints = (0..<10_001).collect { int i ->
            double angle = (2.0d * Math.PI * i) / 10_001.0d
            JsonNode.createArrayNode([
                    JsonNode.createNumberNode(-105.0d + Math.cos(angle) * 0.1d),
                    JsonNode.createNumberNode(39.0d + Math.sin(angle) * 0.1d)
            ])
        }
        JsonNode polygonNode = JsonNode.createObjectNode([
                type       : JsonNode.createStringNode("Polygon"),
                coordinates: JsonNode.createArrayNode([JsonNode.createArrayNode(ringPoints)])
        ])

        when:
        geometrySerde.deserializeFromNode(polygonNode)

        then:
        def ex = thrown(SerdeException)
        ex.message.contains("10000")
    }

    // --- SpatialMappingService tests ---

    @Unroll
    def "toPoint rejects invalid WGS84 coordinates (#lon, #lat)"() {
        when:
        spatialMappingService.toPoint(lon, lat)

        then:
        thrown(IllegalArgumentException)

        where:
        lon                      | lat
        Double.NaN               | 39.0d
        -105.0d                  | Double.NaN
        Double.POSITIVE_INFINITY | 39.0d
        -105.0d                  | Double.NEGATIVE_INFINITY
        -181.0d                  | 39.0d
        181.0d                   | 39.0d
        -105.0d                  | 91.0d
        -105.0d                  | -91.0d
    }

    @Unroll
    def "toPolygon rejects invalid WGS84 coordinates (#badLon, #badLat)"() {
        given:
        List<Coordinates> coords = [
                new Coordinates(0.0d, 0.0d),
                new Coordinates(1.0d, 0.0d),
                new Coordinates(badLon, badLat)
        ]

        when:
        spatialMappingService.toPolygon(coords)

        then:
        thrown(IllegalArgumentException)

        where:
        badLon                   | badLat
        Double.NaN               | 1.0d
        1.0d                     | Double.NaN
        Double.POSITIVE_INFINITY | 1.0d
        1.0d                     | Double.NEGATIVE_INFINITY
        -181.0d                  | 1.0d
        181.0d                   | 1.0d
        1.0d                     | 91.0d
        1.0d                     | -91.0d
    }

    @Unroll
    def "toPolygon rejects null coordinate entries and values (#coords)"() {
        when:
        spatialMappingService.toPolygon(coords)

        then:
        thrown(IllegalArgumentException)

        where:
        coords << [
                [new Coordinates(0.0d, 0.0d), null, new Coordinates(1.0d, 1.0d)],
                [new Coordinates(0.0d, 0.0d), new Coordinates(null, 0.0d), new Coordinates(1.0d, 1.0d)],
                [new Coordinates(0.0d, 0.0d), new Coordinates(1.0d, null), new Coordinates(1.0d, 1.0d)]
        ]
    }

    def "toPolygon rejects polygons with more than 10000 vertices"() {
        given:
        List<Coordinates> coords = (0..<10_001).collect { int i ->
            double angle = (2.0d * Math.PI * i) / 10_001.0d
            new Coordinates(Math.cos(angle), Math.sin(angle))
        }

        when:
        spatialMappingService.toPolygon(coords)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("10000")
    }

    // --- JtsPointConverter and JtsPolygonConverter type guard tests ---

    def "JtsPointConverter rejects WKB representing a Polygon"() {
        given:
        Polygon polygon = geometryFactory.createPolygon([
                new Coordinate(-105.0d, 39.0d),
                new Coordinate(-104.0d, 39.0d),
                new Coordinate(-104.0d, 40.0d),
                new Coordinate(-105.0d, 40.0d),
                new Coordinate(-105.0d, 39.0d)
        ] as Coordinate[])
        PGobject polygonWkb = polygonConverter.convertToPersistedValue(polygon, ConversionContext.DEFAULT)

        when:
        pointConverter.convertToEntityValue(polygonWkb, ConversionContext.DEFAULT)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Expected Point geometry")
    }

    def "JtsPolygonConverter rejects Point values on write"() {
        given:
        Point point = geometryFactory.createPoint(new Coordinate(-105.0d, 39.0d))

        when:
        polygonConverter.convertToPersistedValue(point, ConversionContext.DEFAULT)

        then:
        def writeEx = thrown(IllegalArgumentException)
        writeEx.message.contains("Expected Polygon or MultiPolygon")
    }

    def "JtsPolygonConverter rejects Point WKB values on read"() {
        given:
        Point point = geometryFactory.createPoint(new Coordinate(-105.0d, 39.0d))
        PGobject pointWkb = pointConverter.convertToPersistedValue(point, ConversionContext.DEFAULT)

        when:
        polygonConverter.convertToEntityValue(pointWkb, ConversionContext.DEFAULT)

        then:
        def readEx = thrown(IllegalArgumentException)
        readEx.message.contains("Expected Polygon or MultiPolygon")
    }

    // --- DTO Bean Validation tests ---

    @Unroll
    def "#requestType rejects javascript URLs for websiteUrl"() {
        given:
        def request = requestType == "CreateOrganizationRequest"
                ? new CreateOrganizationRequest("Mutual Aid Group", "javascript:alert(1)", null, true)
                : new UpdateOrganizationRequest("Mutual Aid Group", "javascript:alert(1)", null, true)

        when:
        def violations = validator.validate(request)

        then:
        verifyAll {
            violations.size() == 1
            violations[0].propertyPath.toString() == "websiteUrl"
        }

        where:
        requestType << ["CreateOrganizationRequest", "UpdateOrganizationRequest"]
    }

    @Unroll
    def "#requestType rejects endTime before startTime"() {
        given:
        OffsetDateTime start = OffsetDateTime.of(2026, 6, 15, 14, 0, 0, 0, ZoneOffset.UTC)
        OffsetDateTime end = start.minusHours(2)
        def request = requestType == "CreateShiftRequest"
                ? new CreateShiftRequest(UUID.randomUUID(), true, null, start, end, [])
                : new UpdateShiftRequest(true, null, start, end, [])

        when:
        def violations = validator.validate(request)

        then:
        verifyAll {
            violations.size() == 1
            violations[0].message == "Shift start time must be before end time."
        }

        where:
        requestType << ["CreateShiftRequest", "UpdateShiftRequest"]
    }

    // --- BoundaryIngestionService audit guard test ---

    def "BoundaryIngestionService rejects null actorUserId when AdministrativeRegionService is configured"() {
        given:
        AdministrativeRegionService regionService = Mock(AdministrativeRegionService)
        String geoJson = '''{
            "type": "Polygon",
            "coordinates": [[
                [-105.1, 39.7],
                [-104.9, 39.7],
                [-104.9, 39.8],
                [-105.1, 39.8],
                [-105.1, 39.7]
            ]]
        }'''
        StubHttpClient httpClient = new StubHttpClient(sendHandler: { HttpRequest req, HttpResponse.BodyHandler handler ->
            new StubHttpResponse<InputStream>(200, new ByteArrayInputStream(geoJson.getBytes(StandardCharsets.UTF_8)))
        })

        ObjectMapper mockFriendlyMapper = Mock(ObjectMapper) {
            readValue(_ as byte[], JsonNode) >> objectMapper.readValue(geoJson, JsonNode)
            writeValueAsString(_ as Geometry) >> geoJson
        }

        BoundaryIngestionService ingestionService = new BoundaryIngestionService(
                regionService,
                null,
                mockFriendlyMapper,
                geometrySerde,
                httpClient,
                "tigerweb.geo.census.gov",
                "/arcgis/rest/services/TIGERweb/"
        )
        UUID regionId = UUID.randomUUID()
        IngestBoundaryRequest request = new IngestBoundaryRequest(
                "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/query",
                0.001d
        )

        when:
        ingestionService.ingestBoundary(regionId, request, null)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("actorUserId must not be null")
        0 * regionService.applyIngestedBoundary(*_)
    }

    // --- Validator null-input and AuditLogRequest DTO tests ---

    @Unroll
    def "ValidTimeRangeValidator accepts #caseLabel"() {
        given:
        ValidTimeRangeValidator rangeValidator = new ValidTimeRangeValidator()

        expect:
        rangeValidator.isValid(range, null)

        where:
        caseLabel                       | range
        "null range"                    | null
        "range with missing start time" | ([startTime: { null }, endTime: { OffsetDateTime.of(2026, 6, 15, 10, 0, 0, 0, ZoneOffset.UTC) }] as ShiftTimeRange)
        "range with missing end time"   | ([startTime: { OffsetDateTime.of(2026, 6, 15, 10, 0, 0, 0, ZoneOffset.UTC) }, endTime: { null }] as ShiftTimeRange)
    }

    def "ValidShiftLocationValidator accepts null ShiftLocation"() {
        given:
        ValidShiftLocationValidator locationValidator = new ValidShiftLocationValidator()

        expect:
        locationValidator.isValid(null, null)
    }

    def "CreateOrganizationAuditLogRequest stores fields and passes validation"() {
        given:
        UUID orgId = UUID.randomUUID()
        UUID actorId = UUID.randomUUID()
        def request = new CreateOrganizationAuditLogRequest(orgId, actorId, "UNVERIFIED", "VERIFIED", "Reviewed docs")

        expect:
        verifyAll {
            validator.validate(request).isEmpty()
            request.organizationId() == orgId
            request.actorId() == actorId
            request.previousStatus() == "UNVERIFIED"
            request.newStatus() == "VERIFIED"
            request.reason() == "Reviewed docs"
        }
    }

    def "CreateProjectAuditLogRequest stores fields and passes validation"() {
        given:
        UUID projectId = UUID.randomUUID()
        UUID actorId = UUID.randomUUID()
        def request = new CreateProjectAuditLogRequest(projectId, actorId, AuditAction.APPROVED)

        expect:
        verifyAll {
            validator.validate(request).isEmpty()
            request.projectId() == projectId
            request.actorId() == actorId
            request.action() == AuditAction.APPROVED
        }
    }

    def "UpdateOrganizationAuditLogRequest stores fields and passes validation"() {
        given:
        def request = new UpdateOrganizationAuditLogRequest("VERIFIED", "SUSPENDED", "Expired")

        expect:
        verifyAll(request) {
            validator.validate(it).isEmpty()
            previousStatus() == "VERIFIED"
            newStatus() == "SUSPENDED"
            reason() == "Expired"
        }
    }

    def "UpdateProjectAuditLogRequest stores fields and passes validation"() {
        given:
        def request = new UpdateProjectAuditLogRequest(AuditAction.EDITED)

        expect:
        verifyAll(request) {
            validator.validate(it).isEmpty()
            action() == AuditAction.EDITED
        }
    }

    @Unroll
    def "#requestName reports #expectedViolations violations when required fields are invalid"() {
        expect:
        validator.validate(request).size() == expectedViolations

        where:
        requestName                         | request                                                             | expectedViolations
        "CreateOrganizationAuditLogRequest" | new CreateOrganizationAuditLogRequest(null, null, "", " ", null)    | 4
        "CreateProjectAuditLogRequest"      | new CreateProjectAuditLogRequest(null, null, null)                  | 3
        "UpdateOrganizationAuditLogRequest" | new UpdateOrganizationAuditLogRequest("", " ", null)                | 2
        "UpdateProjectAuditLogRequest"      | new UpdateProjectAuditLogRequest(null)                              | 1
    }
}
