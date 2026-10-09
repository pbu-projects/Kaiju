package lol.pbu.kaiju.controller

import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.Boundary
import lol.pbu.kaiju.dto.CoordinateDto
import lol.pbu.kaiju.dto.CreateBoundaryCommand
import lol.pbu.kaiju.dto.UpdateBoundaryCommand
import lol.pbu.kaiju.repository.BoundaryRepository
import net.datafaker.Faker
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel
import spock.lang.Shared
import spock.lang.Unroll

import java.util.UUID

@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.oauth2.enabled", value = "false")
@Property(name = "micronaut.security.token.jwt.enabled", value = "false")
@MicronautTest(transactional = false)
class BoundaryControllerSpec extends BaseControllerSpec {

    @Inject
    BoundaryRepository boundaryRepository

    @Shared
    Faker faker = new Faker()

    @Shared
    GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326)

    private Polygon createPolygon() {
        Coordinate[] coords = [
                new Coordinate(0, 0),
                new Coordinate(0, 1),
                new Coordinate(1, 1),
                new Coordinate(1, 0),
                new Coordinate(0, 0)
        ]
        geometryFactory.createPolygon(coords)
    }

    private List<CoordinateDto> createCoordinateDtos() {
        [
                new CoordinateDto(0.0, 0.0),
                new CoordinateDto(0.0, 1.0),
                new CoordinateDto(1.0, 1.0),
                new CoordinateDto(1.0, 0.0),
                new CoordinateDto(0.0, 0.0)
        ]
    }

    def setup() {
        sql.execute("INSERT INTO boundaries (name, geom) VALUES ('Test Boundary A', ST_GeomFromText('POLYGON((0 0, 0 1, 1 1, 1 0, 0 0))', 4326))")
        sql.execute("INSERT INTO boundaries (name, geom) VALUES ('Test Boundary B', ST_GeomFromText('POLYGON((0 0, 0 2, 2 2, 2 0, 0 0))', 4326))")
    }

    def cleanup() {
        sql.execute("DELETE FROM boundaries WHERE name LIKE 'Test Boundary %' OR name LIKE 'Updated Boundary %' OR name LIKE 'Temporary Boundary %'")
    }

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid boundary"() {
        given: "a new valid boundary command"
        String name = "Test Boundary ${faker.address().city()}"
        def command = new CreateBoundaryCommand(name, createCoordinateDtos())

        when: "the boundary is added via HTTP POST"
        def response = client.exchange(asGlobalAdmin(HttpRequest.POST("/boundaries", command)), Boundary)
        Boundary saved = response.body()

        then: "the boundary is persisted with 200 OK and generated ID"
        response.status == HttpStatus.OK
        verifyAll {
            saved.id() != null
            saved.name() == name
            saved.geom() != null
        }

        and: "it can be retrieved from the database"
        def result = sql.firstRow("SELECT * FROM boundaries WHERE id = ?", [saved.id()])
        verifyAll(result) {
            saved.id() == id
            saved.name() == result.name
        }
    }

    @Unroll
    def "CREATE | should fail to save boundary with invalid data: #testCase"(String testCase, Map payload) {
        when: "an attempt is made to add a boundary with invalid data via HTTP POST"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/boundaries", payload)), Boundary)

        then: "a 400 Bad Request exception is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        testCase                    | payload
        "Null Name"                 | [name: null, coordinates: [[longitude: 0.0, latitude: 0.0], [longitude: 0.0, latitude: 1.0], [longitude: 1.0, latitude: 1.0]]]
        "Blank Name"                | [name: " ", coordinates: [[longitude: 0.0, latitude: 0.0], [longitude: 0.0, latitude: 1.0], [longitude: 1.0, latitude: 1.0]]]
        "Name Too Long"             | [name: "A" * 256, coordinates: [[longitude: 0.0, latitude: 0.0], [longitude: 0.0, latitude: 1.0], [longitude: 1.0, latitude: 1.0]]]
        "Null Coordinates"          | [name: "Valid Name", coordinates: null]
        "Less Than 3 Coordinates"   | [name: "Valid Name", coordinates: [[longitude: 0.0, latitude: 0.0], [longitude: 0.0, latitude: 1.0]]]
        "Longitude Out of Bounds"   | [name: "Valid Name", coordinates: [[longitude: 181.0, latitude: 0.0], [longitude: 0.0, latitude: 1.0], [longitude: 1.0, latitude: 1.0]]]
        "Latitude Out of Bounds"    | [name: "Valid Name", coordinates: [[longitude: 0.0, latitude: -91.0], [longitude: 0.0, latitude: 1.0], [longitude: 1.0, latitude: 1.0]]]
    }

    def "CREATE | should reject unauthenticated POST /boundaries with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to create a boundary"
        client.exchange(HttpRequest.POST("/boundaries", [
                name       : "Unauth Boundary",
                coordinates: [[longitude: 0.0, latitude: 0.0], [longitude: 0.0, latitude: 1.0], [longitude: 1.0, latitude: 1.0]]
        ]), Boundary)

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** READ Tests **********/

    def "READ | should retrieve an existing boundary by ID"() {
        given: "an existing boundary"
        def boundary = boundaryRepository.save(new Boundary(null, "Test Boundary Read", createPolygon()))
        UUID id = boundary.id()

        when: "the boundary is requested by its ID via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/boundaries/${id}")), Boundary)

        then: "the correct boundary is returned with 200 OK"
        response.status == HttpStatus.OK
        verifyAll {
            response.body().id() == id
            response.body().name() == "Test Boundary Read"
        }
    }

    def "READ | should return 404 for a non-existent boundary ID"() {
        when: "a non-existent boundary is requested via HTTP GET"
        client.exchange(asGlobalAdmin(HttpRequest.GET("/boundaries/${UUID.randomUUID()}")), Boundary)

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "READ | should reject unauthenticated GET /boundaries/{id} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to get a boundary"
        client.exchange(HttpRequest.GET("/boundaries/${UUID.randomUUID()}"), Boundary)

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** UPDATE Tests **********/

    def "UPDATE | should successfully update an existing boundary"() {
        given: "an existing boundary"
        def boundary = boundaryRepository.save(new Boundary(null, "Original Boundary Name", createPolygon()))
        UUID id = boundary.id()
        def newName = "Updated Boundary ${faker.address().city()}"
        def updateCommand = new UpdateBoundaryCommand(newName, createCoordinateDtos())

        when: "the boundary is updated via HTTP PUT"
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/boundaries/${id}", updateCommand)), Boundary)
        Boundary updated = response.body()

        then: "the returned boundary contains the updated data with 200 OK"
        response.status == HttpStatus.OK
        verifyAll {
            updated.id() == id
            updated.name() == newName
        }

        and: "the changes are persisted in the database"
        def dbResult = sql.firstRow("SELECT name FROM boundaries WHERE id = ?", [id])
        verifyAll(dbResult) {
            name == newName
        }
    }

    def "UPDATE | should fail to update a non-existent boundary"() {
        given: "a random non-existent ID and an update command"
        def nonExistentId = UUID.randomUUID()
        def updateCommand = new UpdateBoundaryCommand("Test Boundary", createCoordinateDtos())

        when: "an update is attempted via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/boundaries/${nonExistentId}", updateCommand)), Boundary)

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "UPDATE | should fail to update boundary with invalid data: #testCase"(String testCase, Map payload) {
        given: "an existing boundary"
        def boundary = boundaryRepository.save(new Boundary(null, "Test Update Invalid", createPolygon()))
        UUID id = boundary.id()

        when: "an update with invalid data is attempted via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/boundaries/${id}", payload)), Boundary)

        then: "a 400 BAD REQUEST status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        testCase                  | payload
        "Blank Name"              | [name: " ", coordinates: [[longitude: 0.0, latitude: 0.0], [longitude: 0.0, latitude: 1.0], [longitude: 1.0, latitude: 1.0]]]
        "Less Than 3 Coordinates" | [name: "Valid", coordinates: [[longitude: 0.0, latitude: 0.0], [longitude: 0.0, latitude: 1.0]]]
    }

    def "UPDATE | should reject unauthenticated PUT /boundaries/{id} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to update a boundary"
        client.exchange(HttpRequest.PUT("/boundaries/${UUID.randomUUID()}", [
                name       : "Unauth Boundary",
                coordinates: [[longitude: 0.0, latitude: 0.0], [longitude: 0.0, latitude: 1.0], [longitude: 1.0, latitude: 1.0]]
        ]), Boundary)

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing boundary"() {
        given: "a new boundary to be deleted"
        def tempBoundary = new Boundary(null, "Temporary Boundary to Delete", createPolygon())
        def saved = boundaryRepository.save(tempBoundary)
        UUID id = saved.id()
        assert boundaryRepository.existsById(id)

        when: "the boundary is deleted via HTTP DELETE"
        def response = client.exchange(asGlobalAdmin(HttpRequest.DELETE("/boundaries/${id}")))

        then: "the response is 200 OK"
        response.status == HttpStatus.OK

        and: "the boundary no longer exists in the repository or database"
        verifyAll {
            !boundaryRepository.findById(id).isPresent()
            sql.firstRow("SELECT count(*) as count FROM boundaries WHERE id = ?", [id]).count == 0
        }
    }

    def "DELETE | should fail to delete a non-existent boundary"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a delete is attempted via HTTP DELETE"
        client.exchange(asGlobalAdmin(HttpRequest.DELETE("/boundaries/${nonExistentId}")))

        then: "a 404 NOT FOUND status is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "DELETE | should reject unauthenticated DELETE /boundaries/{id} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to delete a boundary"
        client.exchange(HttpRequest.DELETE("/boundaries/${UUID.randomUUID()}"))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** LIST Tests **********/

    def "LIST | should retrieve boundaries with pagination"() {
        when: "requesting boundaries via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/boundaries?size=5")), Map)

        then: "the response is 200 OK with content list"
        response.status == HttpStatus.OK
        Map body = response.body()
        body.content instanceof List
        body.content.size() >= 2
    }

    def "LIST | should reject unauthenticated GET /boundaries with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to list boundaries"
        client.exchange(HttpRequest.GET("/boundaries"))

        then: "a 401 UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }
}
