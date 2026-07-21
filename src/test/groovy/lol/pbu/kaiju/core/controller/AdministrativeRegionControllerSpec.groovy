package lol.pbu.kaiju.core.controller

import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.exceptions.HttpStatusException
import jakarta.inject.Inject
import jakarta.validation.ValidationException
import lol.pbu.kaiju.core.domain.AdministrativeRegion
import lol.pbu.kaiju.core.repository.AdministrativeRegionRepository
import net.datafaker.Faker
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.PrecisionModel
import spock.lang.Shared
import spock.lang.Unroll

class AdministrativeRegionControllerSpec extends BaseControllerSpec {

    @Inject
    AdministrativeRegionRepository administrativeRegionRepository

    @Inject
    AdministrativeRegionController administrativeRegionController

    @Shared
    Faker faker = new Faker()

    @Shared
    GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326)

    private org.locationtech.jts.geom.Geometry createTestPolygon() {
        Coordinate[] coords = [
                new Coordinate(-105.0, 39.0),
                new Coordinate(-104.0, 39.0),
                new Coordinate(-104.0, 40.0),
                new Coordinate(-105.0, 40.0),
                new Coordinate(-105.0, 39.0)
        ]
        return geometryFactory.createPolygon(coords)
    }

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid administrative region"() {
        given: "a new valid administrative region"
        def geom = createTestPolygon()
        def newRegion = new AdministrativeRegion(
                null,
                "Region ${faker.address().state()}",
                null,
                geom
        )

        when: "the administrative region is added"
        AdministrativeRegion saved = administrativeRegionController.addAdministrativeRegion(newRegion)

        then: "the administrative region is persisted with a generated ID"
        verifyAll {
            saved.id() != null
            saved.name() == newRegion.name()
        }

        and: "it can be retrieved from the database"
        def result = sql.firstRow("SELECT * FROM administrative_regions WHERE id = ?", [saved.id()])
        verifyAll(result) {
            saved.id() == id
            saved.name() == name
        }
    }

    @Unroll
    def "CREATE | should fail to save administrative region with invalid data: #testCase"(String testCase, AdministrativeRegion region) {
        when: "an attempt is made to add an administrative region with invalid data"
        administrativeRegionController.addAdministrativeRegion(region)

        then: "an exception is thrown"
        thrown(ValidationException)

        where:
        [testCase, region] << {
            def validGeom = createTestPolygon()
            def validData = [
                    name: "Valid Region",
                    geom: validGeom
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
                def r = new AdministrativeRegion(
                        null,
                        props.name as String,
                        null,
                        props.geom as org.locationtech.jts.geom.Geometry
                )
                [invalidCase.caseName, r]
            }
        }()
    }

    /********** READ Tests **********/

    def "READ | should retrieve an existing administrative region by ID"() {
        given: "an existing administrative region"
        def geom = createTestPolygon()
        def region = administrativeRegionRepository.save(new AdministrativeRegion(null, "Test Region Read", null, geom))
        UUID id = region.id()

        when: "the administrative region is requested by its ID"
        def result = administrativeRegionController.getAdministrativeRegion(id)

        then: "the correct administrative region is returned"
        verifyAll {
            result.isPresent()
            result.get().id() == id
            result.get().name() == "Test Region Read"
        }
    }

    def "READ | should return empty for a non-existent administrative region ID"() {
        when: "a non-existent administrative region is requested"
        def result = administrativeRegionController.getAdministrativeRegion(UUID.randomUUID())

        then: "the result is empty"
        !result.isPresent()
    }

    /********** UPDATE Tests **********/

    def "UPDATE | should successfully update an existing administrative region"() {
        given: "an existing administrative region"
        def geom = createTestPolygon()
        def region = administrativeRegionRepository.save(new AdministrativeRegion(null, "Original Region Name", null, geom))
        UUID id = region.id()
        def newName = "Updated Region ${faker.address().state()}"
        def updateRequest = new AdministrativeRegion(null, newName, null, geom)

        when: "the administrative region is updated"
        AdministrativeRegion updated = administrativeRegionController.updateAdministrativeRegion(id, updateRequest)

        then: "the returned administrative region contains the updated data"
        verifyAll {
            updated.id() == id
            updated.name() == newName
        }

        and: "the changes are persisted in the database"
        def dbResult = sql.firstRow("SELECT name FROM administrative_regions WHERE id = ?", [id])
        verifyAll(dbResult) {
            name == newName
        }
    }

    def "UPDATE | should fail to update a non-existent administrative region"() {
        given: "a random non-existent ID and an update request"
        def nonExistentId = UUID.randomUUID()
        def geom = createTestPolygon()
        def updateRequest = new AdministrativeRegion(null, "Test Region", null, geom)

        when: "an update is attempted"
        administrativeRegionController.updateAdministrativeRegion(nonExistentId, updateRequest)

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }

    def "UPDATE | should handle update of non-existent administrative region gracefully when using no-look"() {
        given: "a random non-existent ID and an update request"
        def nonExistentId = UUID.randomUUID()
        def geom = createTestPolygon()
        def updateRequest = new AdministrativeRegion(null, "Test Region", null, geom)

        when: "a no-look update is attempted"
        administrativeRegionController.updateAdministrativeRegionNoLook(nonExistentId, updateRequest)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing administrative region"() {
        given: "a new administrative region to be deleted"
        def geom = createTestPolygon()
        def tempRegion = new AdministrativeRegion(
                null,
                "Temporary Region to Delete",
                null,
                geom
        )
        def saved = administrativeRegionController.addAdministrativeRegion(tempRegion)
        UUID id = saved.id()
        assert administrativeRegionRepository.existsById(id)

        when: "the administrative region is deleted"
        administrativeRegionController.deleteAdministrativeRegion(id)

        then: "the administrative region no longer exists in the repository or database"
        verifyAll {
            !administrativeRegionRepository.findById(id).isPresent()
            sql.firstRow("SELECT count(*) as count FROM administrative_regions WHERE id = ?", [id]).count == 0
        }
    }

    def "DELETE | should fail to delete a non-existent administrative region"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a delete is attempted"
        administrativeRegionController.deleteAdministrativeRegion(nonExistentId)

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }

    def "DELETE | should handle deletion of non-existent administrative region gracefully when using no-look"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a no-look delete is attempted"
        administrativeRegionController.deleteAdministrativeRegionNoLook(nonExistentId)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    /********** LIST Tests **********/

    def "LIST | should fully drain all administrative regions sequentially using cursors"() {
        setup:
        Set<AdministrativeRegion> allRegions = new LinkedHashSet<>()
        int pageSize = 5
        def pageable = CursoredPageable.from(pageSize, Sort.of(Sort.Order.asc("name")))

        when: "iterating through pages until no more data remains"
        while (pageable != null) {
            CursoredPage<AdministrativeRegion> page = administrativeRegionController.getAdministrativeRegions(pageable)
            allRegions.addAll(page.content)
            pageable = page.hasNext() ? page.nextPageable() : null
        }

        then: "the collected set contains all administrative regions from the database"
        def totalCount = sql.firstRow("SELECT count(*) as count FROM administrative_regions").count
        verifyAll {
            allRegions.size() == totalCount
        }
    }
}
