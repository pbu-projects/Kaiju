package lol.pbu.kaiju.core.controller

import groovy.sql.Sql
import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.validation.ValidationException
import lol.pbu.kaiju.core.domain.Boundary
import lol.pbu.kaiju.core.repository.BoundaryRepository
import net.datafaker.Faker
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

@MicronautTest
class BoundaryControllerSpec extends Specification {

    @Inject
    @Shared
    Connection connection

    @Shared
    Connection standaloneConnection

    @Shared
    Sql sql

    @Inject
    BoundaryRepository boundaryRepository

    @Inject
    BoundaryController boundaryController

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

    def setupSpec() {
        standaloneConnection = DriverManager.getConnection("jdbc:postgresql://localhost:5432/volunteer_monster", "jimmy", "warm-farts-smell-worse")
        sql = new Sql((Connection) Proxy.newProxyInstance(
                Connection.class.classLoader,
                [Connection.class] as Class[],
                { Object proxy, Method method, Object[] args ->
                    try {
                        return method.invoke(connection, args)
                    } catch (Throwable ignored) {
                        return method.invoke(standaloneConnection, args)
                    }
                } as InvocationHandler
        ))
        standaloneConnection.createStatement().execute("INSERT INTO boundaries (name, geom) VALUES ('Test Boundary A', ST_GeomFromText('POLYGON((0 0, 0 1, 1 1, 1 0, 0 0))', 4326))")
        standaloneConnection.createStatement().execute("INSERT INTO boundaries (name, geom) VALUES ('Test Boundary B', ST_GeomFromText('POLYGON((0 0, 0 2, 2 2, 2 0, 0 0))', 4326))")
    }

    def cleanupSpec() {
        standaloneConnection.createStatement().execute("DELETE FROM boundaries WHERE name LIKE 'Test Boundary %'")
        standaloneConnection?.close()
    }

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid boundary"() {
        given: "a new valid boundary"
        def newBoundary = new Boundary(
                null,
                "Test Boundary ${faker.address().city()}",
                createPolygon()
        )

        when: "the boundary is added"
        Boundary saved = boundaryController.addBoundary(newBoundary)

        then: "the boundary is persisted with a generated ID"
        verifyAll {
            saved.id() != null
            saved.name() == newBoundary.name()
        }

        and: "it can be retrieved from the database"
        def result = sql.firstRow("SELECT * FROM boundaries WHERE id = ?", [saved.id()])
        verifyAll(result) {
            saved.id() == id
            saved.name() == name
        }
    }

    @Unroll
    def "CREATE | should fail to save boundary with invalid data: #testCase"(String testCase, Boundary boundary) {
        when: "an attempt is made to add a boundary with invalid data"
        boundaryController.addBoundary(boundary)

        then: "an exception is thrown"
        thrown(ValidationException)

        where:
        [testCase, boundary] << {
            def validData = [
                    name: "Valid Boundary Name",
                    geom: createPolygon()
            ]

            def invalidCases = [
                    [field: 'name', value: null, caseName: "Null Name"],
                    [field: 'name', value: ' ', caseName: "Blank Name"],
                    [field: 'name', value: 'A' * 256, caseName: "Name Too Long"],
                    [field: 'geom', value: null, caseName: "Null Geometry"]
            ]

            return invalidCases.collect { invalidCase ->
                def props = new HashMap(validData)
                props[invalidCase.field] = invalidCase.value
                def b = new Boundary(
                        null,
                        props.name as String,
                        props.geom as Polygon
                )
                [invalidCase.caseName, b]
            }
        }()
    }

    /********** READ Tests **********/

    @Unroll
    @SuppressWarnings("GroovyAssignabilityCheck")
    def "READ | should retrieve an existing boundary by ID: #name"(UUID id, String name) {
        when: "the boundary is requested by its ID"
        def result = boundaryController.getBoundary(id)

        then: "the correct boundary is returned"
        verifyAll {
            result.isPresent()
            result.get().id() == id
            result.get().name() == name
        }

        where:
        [id, name] << sql.rows("SELECT id, name FROM boundaries LIMIT 3").collect { [it.id, it.name] }
    }

    def "READ | should return empty for a non-existent boundary ID"() {
        when: "a non-existent boundary is requested"
        def result = boundaryController.getBoundary(UUID.randomUUID())

        then: "the result is empty"
        !result.isPresent()
    }

    /********** UPDATE Tests **********/

    @Unroll
    @SuppressWarnings("GroovyAssignabilityCheck")
    def "UPDATE | should successfully update an existing boundary: #originalName"(UUID id, String originalName) {
        given: "an existing boundary's details"
        def newName = "Updated ${faker.address().city()}"
        def updateRequest = new Boundary(null, newName, createPolygon())

        when: "the boundary is updated"
        Boundary updated = boundaryController.updateBoundary(id, updateRequest)

        then: "the returned boundary contains the updated data"
        verifyAll {
            updated.id() == id
            updated.name() == newName
        }

        and: "the changes are persisted in the database"
        def dbResult = sql.firstRow("SELECT name FROM boundaries WHERE id = ?", [id])
        verifyAll(dbResult) {
            name == newName
        }

        where:
        [id, originalName] << sql.rows("SELECT id, name FROM boundaries LIMIT 2").collect { [it.id, it.name] }
    }

    def "UPDATE | should fail to update a non-existent boundary"() {
        given: "a random non-existent ID and an update request"
        def nonExistentId = UUID.randomUUID()
        def updateRequest = new Boundary(null, "Test Boundary", createPolygon())

        when: "an update is attempted"
        boundaryController.updateBoundary(nonExistentId, updateRequest)

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }

    def "UPDATE | should handle update of non-existent boundary gracefully when using no-look"() {
        given: "a random non-existent ID and an update request"
        def nonExistentId = UUID.randomUUID()
        def updateRequest = new Boundary(null, "Test Boundary", createPolygon())

        when: "a no-look update is attempted"
        boundaryController.updateBoundaryNoLook(nonExistentId, updateRequest)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing boundary"() {
        given: "a new boundary to be deleted"
        def tempBoundary = new Boundary(
                null,
                "Temporary Boundary to Delete",
                createPolygon()
        )
        def saved = boundaryController.addBoundary(tempBoundary)
        UUID id = saved.id()
        assert boundaryRepository.existsById(id)

        when: "the boundary is deleted"
        boundaryController.deleteBoundary(id)

        then: "the boundary no longer exists in the repository or database"
        verifyAll {
            !boundaryRepository.findById(id).isPresent()
            sql.firstRow("SELECT count(*) as count FROM boundaries WHERE id = ?", [id]).count == 0
        }
    }

    def "DELETE | should fail to delete a non-existent boundary"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a delete is attempted"
        boundaryController.deleteBoundary(nonExistentId)

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }

    def "DELETE | should handle deletion of non-existent boundary gracefully when using no-look"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a no-look delete is attempted"
        boundaryController.deleteBoundaryNoLook(nonExistentId)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    /********** LIST Tests **********/

    def "LIST | should fully drain all boundaries sequentially using cursors"() {
        setup:
        Set<Boundary> allBoundaries = new LinkedHashSet<>()
        int pageSize = 5
        def pageable = CursoredPageable.from(pageSize, Sort.of(Sort.Order.asc("name")))

        when: "iterating through pages until no more data remains"
        while (pageable != null) {
            CursoredPage<Boundary> page = boundaryController.getBoundaries(pageable)
            allBoundaries.addAll(page.content)
            pageable = page.hasNext() ? page.nextPageable() : null
        }

        then: "the collected set contains all boundaries from the database"
        def totalCount = sql.firstRow("SELECT count(*) as count FROM boundaries").count
        verifyAll {
            allBoundaries.size() == totalCount
            allBoundaries.size() >= 2
        }
    }
}
