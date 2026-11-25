package lol.pbu.kaiju.service

import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.serde.ObjectMapper
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.controller.BaseControllerSpec
import lol.pbu.kaiju.domain.AdministrativeRegion
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.dto.Coordinates
import lol.pbu.kaiju.dto.CreateAdministrativeRegionRequest
import lol.pbu.kaiju.dto.IngestBoundaryResponse
import lol.pbu.kaiju.dto.UpdateAdministrativeRegionRequest
import lol.pbu.kaiju.model.CoveringRegion
import lol.pbu.kaiju.model.RegionAuditAction
import lol.pbu.kaiju.model.RegionCoordinateResolution
import lol.pbu.kaiju.repository.AdministrativeRegionAuditLogRepository
import lol.pbu.kaiju.repository.AdministrativeRegionRepository
import lol.pbu.kaiju.repository.UserRepository
import org.locationtech.jts.geom.Polygon
import spock.lang.Unroll

import java.util.UUID

@MicronautTest(transactional = false)
class AdministrativeRegionServiceSpec extends BaseControllerSpec {

    @Inject
    AdministrativeRegionService regionService

    @Inject
    AdministrativeRegionRepository regionRepository

    @Inject
    AdministrativeRegionAuditLogRepository auditLogRepository

    @Inject
    SpatialMappingService spatialMappingService

    private User testAdminUser

    def setup() {
        testAdminUser = dataBuilder.user().asAdmin().persist()
    }

    private AdministrativeRegion createRegionWithMetadata(
            String name,
            Integer level,
            String stateCode,
            String timeZone,
            double minLon,
            double minLat,
            double maxLon,
            double maxLat
    ) {
        List<Coordinates> coords = [
                new Coordinates(minLon, minLat),
                new Coordinates(maxLon, minLat),
                new Coordinates(maxLon, maxLat),
                new Coordinates(minLon, maxLat),
                new Coordinates(minLon, minLat)
        ]
        Polygon polygon = spatialMappingService.toPolygon(coords)
        AdministrativeRegion region = new AdministrativeRegion(
                null,
                name,
                null,
                polygon,
                level,
                "US",
                stateCode,
                timeZone,
                null
        )
        AdministrativeRegion saved = regionRepository.save(region)
        track(saved)
        return saved
    }

    private List<Coordinates> validCoordinates() {
        [
                new Coordinates(-105.0, 39.0),
                new Coordinates(-104.0, 39.0),
                new Coordinates(-104.0, 40.0),
                new Coordinates(-105.0, 40.0),
                new Coordinates(-105.0, 39.0)
        ]
    }

    private List<Coordinates> bowtieCoordinates() {
        // Self-intersecting figure-8 bowtie polygon (topologically invalid)
        [
                new Coordinates(0.0, 0.0),
                new Coordinates(2.0, 2.0),
                new Coordinates(2.0, 0.0),
                new Coordinates(0.0, 2.0),
                new Coordinates(0.0, 0.0)
        ]
    }

    def "CONSTRUCTOR | should create administrative region via 4-argument constructor with null metadata"() {
        given: "a new administrative region instantiated with minimal arguments"
        UUID expectedId = UUID.randomUUID()
        AdministrativeRegion region = new AdministrativeRegion(expectedId, "Test Minimal", null, null)

        expect: "metadata fields default to null"
        verifyAll(region) {
            id() == expectedId
            name() == "Test Minimal"
            parentRegionId() == null
            geom() == null
            level() == null
            countryCode() == null
            stateCode() == null
            timeZone() == null
            geoid() == null
        }
    }

    def "QUERY | lists administrative regions in pages"() {
        given: "an existing administrative region"
        def createRequest = new CreateAdministrativeRegionRequest("Query Region", null, validCoordinates())
        AdministrativeRegion region = regionService.createAdministrativeRegion(createRequest, testAdminUser.id())
        track(region)

        when: "querying regions with a pageable"
        def page = regionService.getAdministrativeRegions(CursoredPageable.from(10, Sort.UNSORTED))

        then: "regions page is returned with content"
        verifyAll(page) {
            page != null
            !content.isEmpty()
            content.size() <= 10
        }
    }

    def "QUERY | finds an administrative region by ID"() {
        given: "an existing administrative region"
        def createRequest = new CreateAdministrativeRegionRequest("Query Region", null, validCoordinates())
        AdministrativeRegion region = regionService.createAdministrativeRegion(createRequest, testAdminUser.id())
        track(region)

        when: "querying region by id"
        Optional<AdministrativeRegion> found = regionService.getAdministrativeRegionById(region.id())

        then: "the region is returned"
        verifyAll {
            found.isPresent()
            found.get().id() == region.id()
            found.get().name() == "Query Region"
        }
    }

    def "CREATE | should create an administrative region and record an immutable audit log"() {
        given: "a valid create request"
        def request = new CreateAdministrativeRegionRequest("Metro North", null, validCoordinates())

        when: "the region is created via the service"
        AdministrativeRegion region = regionService.createAdministrativeRegion(request, testAdminUser.id())
        track(region)

        then: "the region is saved"
        verifyAll {
            region.id() != null
            region.name() == "Metro North"
        }

        and: "an audit log is recorded with CREATE action"
        def logs = auditLogRepository.findByRegionId(region.id(), CursoredPageable.from(10, Sort.UNSORTED))
        verifyAll {
            logs.content.size() == 1
            logs.content[0].action() == RegionAuditAction.CREATE
            logs.content[0].actor().id() == testAdminUser.id()
            logs.content[0].metadata().contains("Metro North")
        }
    }

    def "CREATE | should fail when parent administrative region does not exist"() {
        given: "a request referencing a non-existent parent ID"
        UUID missingParentId = UUID.randomUUID()
        def request = new CreateAdministrativeRegionRequest("Child Region", missingParentId, validCoordinates())

        when: "attempting to create the region"
        regionService.createAdministrativeRegion(request, testAdminUser.id())

        then: "HTTP 400 Bad Request is thrown"
        def e = thrown(HttpStatusException)
        verifyAll {
            e.status == HttpStatus.BAD_REQUEST
        }
    }

    def "CREATE | should fail when geometry is topologically invalid"() {
        given: "a request with a self-intersecting polygon"
        def request = new CreateAdministrativeRegionRequest("Invalid Region", null, bowtieCoordinates())

        when: "attempting to create the region"
        regionService.createAdministrativeRegion(request, testAdminUser.id())

        then: "HTTP 422 Unprocessable Entity is thrown"
        def e = thrown(HttpStatusException)
        verifyAll {
            e.status == HttpStatus.UNPROCESSABLE_ENTITY
        }
    }

    def "CREATE | should fail when actor user does not exist"() {
        given: "a non-existent actor UUID"
        UUID missingUserId = UUID.randomUUID()
        def request = new CreateAdministrativeRegionRequest("Valid Region", null, validCoordinates())

        when: "attempting to create the region"
        regionService.createAdministrativeRegion(request, missingUserId)

        then: "HTTP 401 Unauthorized is thrown"
        def e = thrown(HttpStatusException)
        verifyAll {
            e.status == HttpStatus.UNAUTHORIZED
        }
    }

    def "CREATE | should default to level 2 when parent administrative region has null level"() {
        given: "a parent administrative region persisted with null level"
        AdministrativeRegion parent = dataBuilder.region().withName("Parent With Null Level").persist()
        track(parent)

        and: "a request to create a child region referencing the parent"
        def request = new CreateAdministrativeRegionRequest("Child With Level Fallback", parent.id(), validCoordinates())

        when: "creating the child region"
        AdministrativeRegion child = regionService.createAdministrativeRegion(request, testAdminUser.id())
        track(child)

        then: "the child region is assigned level 2"
        verifyAll(child) {
            id() != null
            parentRegionId() == parent.id()
            level() == 2
        }
    }

    def "UPDATE | should update an existing administrative region and record an audit log"() {
        given: "an existing administrative region"
        def createRequest = new CreateAdministrativeRegionRequest("Original Name", null, validCoordinates())
        AdministrativeRegion initial = regionService.createAdministrativeRegion(createRequest, testAdminUser.id())
        track(initial)

        and: "an update request"
        def updateRequest = new UpdateAdministrativeRegionRequest("Renamed Region", null, validCoordinates())

        when: "updating the administrative region"
        AdministrativeRegion updated = regionService.updateAdministrativeRegion(initial.id(), updateRequest, testAdminUser.id())

        then: "the region name is updated"
        verifyAll {
            updated.id() == initial.id()
            updated.name() == "Renamed Region"
        }

        and: "both CREATE and UPDATE audit logs are present"
        def logs = auditLogRepository.findByRegionId(initial.id(), CursoredPageable.from(10, Sort.UNSORTED))
        verifyAll {
            logs.content.size() == 2
            logs.content.any { it.action() == RegionAuditAction.UPDATE && it.metadata().contains("Original Name") && it.metadata().contains("Renamed Region") }
        }
    }

    def "UPDATE | should reject an administrative region setting itself as parent"() {
        given: "an existing administrative region"
        def createRequest = new CreateAdministrativeRegionRequest("Self Parent Region", null, validCoordinates())
        AdministrativeRegion region = regionService.createAdministrativeRegion(createRequest, testAdminUser.id())
        track(region)

        and: "an update request setting parentRegionId to its own ID"
        def updateRequest = new UpdateAdministrativeRegionRequest("Self Parent Region", region.id(), validCoordinates())

        when: "attempting to update the region"
        regionService.updateAdministrativeRegion(region.id(), updateRequest, testAdminUser.id())

        then: "HTTP 400 Bad Request is thrown"
        def e = thrown(HttpStatusException)
        verifyAll {
            e.status == HttpStatus.BAD_REQUEST
        }
    }

    def "UPDATE | should reject setting a non-existent parent administrative region"() {
        given: "an existing administrative region"
        def createRequest = new CreateAdministrativeRegionRequest("Region", null, validCoordinates())
        AdministrativeRegion region = regionService.createAdministrativeRegion(createRequest, testAdminUser.id())
        track(region)

        and: "an update request setting a non-existent parent ID"
        def updateRequest = new UpdateAdministrativeRegionRequest("Region", UUID.randomUUID(), validCoordinates())

        when: "attempting to update the region"
        regionService.updateAdministrativeRegion(region.id(), updateRequest, testAdminUser.id())

        then: "HTTP 400 Bad Request is thrown"
        def e = thrown(HttpStatusException)
        verifyAll {
            e.status == HttpStatus.BAD_REQUEST
        }
    }

    def "UPDATE | should return 404 when updating non-existent region"() {
        given: "a non-existent region ID"
        UUID missingId = UUID.randomUUID()
        def updateRequest = new UpdateAdministrativeRegionRequest("New Name", null, validCoordinates())

        when: "attempting to update"
        regionService.updateAdministrativeRegion(missingId, updateRequest, testAdminUser.id())

        then: "HTTP 404 Not Found is thrown"
        def e = thrown(HttpStatusException)
        verifyAll {
            e.status == HttpStatus.NOT_FOUND
        }
    }

    def "UPDATE | should fail when updating with topologically invalid geometry"() {
        given: "an existing administrative region"
        def createRequest = new CreateAdministrativeRegionRequest("Valid Region Before Update", null, validCoordinates())
        AdministrativeRegion initial = regionService.createAdministrativeRegion(createRequest, testAdminUser.id())
        track(initial)

        and: "an update request with a self-intersecting polygon"
        def updateRequest = new UpdateAdministrativeRegionRequest("Updated Region", null, bowtieCoordinates())

        when: "attempting to update the region"
        regionService.updateAdministrativeRegion(initial.id(), updateRequest, testAdminUser.id())

        then: "HTTP 422 Unprocessable Entity is thrown"
        def e = thrown(HttpStatusException)
        verifyAll {
            e.status == HttpStatus.UNPROCESSABLE_ENTITY
        }
    }

    def "DELETE | should delete an administrative region and preserve immutable audit log"() {
        given: "an existing administrative region"
        def createRequest = new CreateAdministrativeRegionRequest("Region To Delete", null, validCoordinates())
        AdministrativeRegion region = regionService.createAdministrativeRegion(createRequest, testAdminUser.id())

        when: "deleting the administrative region"
        regionService.deleteAdministrativeRegion(region.id(), testAdminUser.id())

        then: "the region no longer exists in the repository"
        verifyAll {
            !regionRepository.existsById(region.id())
        }

        and: "both CREATE and DELETE audit logs persist intact"
        def logs = auditLogRepository.findByRegionId(region.id(), CursoredPageable.from(10, Sort.UNSORTED))
        verifyAll {
            logs.content.size() == 2
            logs.content.any { it.action() == RegionAuditAction.DELETE && it.metadata().contains("Region To Delete") }
        }
    }

    def "DELETE | should return 404 when deleting a non-existent administrative region"() {
        given: "a non-existent region ID"
        UUID missingId = UUID.randomUUID()

        when: "attempting to delete"
        regionService.deleteAdministrativeRegion(missingId, testAdminUser.id())

        then: "HTTP 404 Not Found is thrown"
        def e = thrown(HttpStatusException)
        verifyAll {
            e.status == HttpStatus.NOT_FOUND
        }
    }

    def "BOUNDARY | should apply ingested boundary to administrative region"() {
        given: "an existing administrative region"
        def createRequest = new CreateAdministrativeRegionRequest("Boundary Test Region", null, validCoordinates())
        AdministrativeRegion region = regionService.createAdministrativeRegion(createRequest, testAdminUser.id())
        track(region)

        and: "a valid geojson polygon string"
        String validGeoJson = '''{
            "type": "Polygon",
            "coordinates": [[
                [-105.1, 39.7],
                [-104.9, 39.7],
                [-104.9, 39.8],
                [-105.1, 39.8],
                [-105.1, 39.7]
            ]]
        }'''

        when: "applying the boundary update"
        IngestBoundaryResponse response = regionService.applyIngestedBoundary(
                region.id(),
                validGeoJson,
                0.001d,
                "https://test.gov",
                testAdminUser.id()
        )

        then: "the response indicates successful boundary application"
        verifyAll(response) {
            regionId() == region.id()
            name() == "Boundary Test Region"
            originalPoints() == 5
            simplifiedPoints() > 0
            valid()
        }

        and: "a BOUNDARY_INGEST audit log is recorded"
        def logs = auditLogRepository.findByRegionId(region.id(), CursoredPageable.from(10, Sort.UNSORTED))
        verifyAll {
            logs.content.size() == 2
            logs.content.any { it.action() == RegionAuditAction.BOUNDARY_INGEST && it.metadata().contains("https://test.gov") }
        }
    }

    @Unroll
    def "RESOLVE | resolves #scenario to #expectedCity and timezone"() {
        when: "resolving coordinates"
        Optional<RegionCoordinateResolution> result = regionService.resolveCoordinates(lat, lon)

        then: "civic boundaries and timezone are resolved"
        verifyAll {
            result.isPresent()
            result.get().state() == "CO"
            result.get().county() == "Denver County"
            result.get().city() == expectedCity
            result.get().timeZone() == "America/Denver"
        }

        where:
        scenario              | lat      | lon       || expectedCity
        "Denver city limits"  | 39.7392d | -104.9903d || "Denver City"
        "downtown core point" | 39.7450d | -104.9920d || "Denver"
    }

    def "RESOLVE | should return empty optional when coordinates are outside coverage"() {
        when: "resolving coordinates outside covered regions"
        Optional<RegionCoordinateResolution> result = regionService.resolveCoordinates(89.0d, -140.0d)

        then: "empty optional is returned"
        verifyAll {
            result.isEmpty()
        }
    }

    def "HIERARCHY | lists all states including Colorado"() {
        when: "querying all states"
        List<AdministrativeRegion> states = regionService.getStates()

        then: "Colorado is present"
        verifyAll {
            !states.isEmpty()
            states.any { it.stateCode() == "CO" || it.name().contains("Colorado") }
        }
    }

    def "HIERARCHY | lists counties in Colorado including Denver County"() {
        when: "querying counties for Colorado"
        List<AdministrativeRegion> counties = regionService.getCounties("CO")

        then: "Denver County is present"
        verifyAll {
            !counties.isEmpty()
            counties.any { it.name().contains("Denver County") }
        }
    }

    def "HIERARCHY | lists cities in Denver County"() {
        when: "querying cities for Denver County"
        List<AdministrativeRegion> cities = regionService.getCities("CO", "Denver County")

        then: "Denver cities are returned"
        verifyAll {
            !cities.isEmpty()
            cities.any { it.name().contains("Denver") }
        }
    }

    def "UPDATE | should preserve hierarchy level, country, state, and timezone metadata"() {
        given: "an existing state region with metadata"
        AdministrativeRegion state = regionService.findState("CO").orElseThrow()

        and: "a county created under that state"
        def createRequest = new CreateAdministrativeRegionRequest("Preserved County", state.id(), validCoordinates())
        AdministrativeRegion created = regionService.createAdministrativeRegion(createRequest, testAdminUser.id())
        track(created)

        verifyAll {
            created.level() == 2
            created.countryCode() == "US"
            created.stateCode() == "CO"
            created.timeZone() == "America/Denver"
        }

        when: "updating the region name and coordinates"
        def updateRequest = new UpdateAdministrativeRegionRequest("Preserved County Renamed", state.id(), validCoordinates())
        AdministrativeRegion updated = regionService.updateAdministrativeRegion(created.id(), updateRequest, testAdminUser.id())

        then: "level, countryCode, stateCode, and timeZone are preserved"
        verifyAll {
            updated.id() == created.id()
            updated.name() == "Preserved County Renamed"
            updated.level() == 2
            updated.countryCode() == "US"
            updated.stateCode() == "CO"
            updated.timeZone() == "America/Denver"
        }

        and: "reloading from the database confirms metadata is preserved in persistence"
        AdministrativeRegion reloaded = regionRepository.findById(created.id()).orElseThrow()
        verifyAll {
            reloaded.level() == 2
            reloaded.countryCode() == "US"
            reloaded.stateCode() == "CO"
            reloaded.timeZone() == "America/Denver"
        }
    }

    @Unroll
    def "RESOLVE | returns empty for invalid coordinates (#scenario)"() {
        when: "resolving out-of-bounds or NaN coordinates"
        Optional<RegionCoordinateResolution> result = regionService.resolveCoordinates(lat, lon)

        then: "empty optional is returned without exception"
        result.isEmpty()

        where:
        scenario                   | lat        | lon
        "latitude above 90"        | 95.0d      | -104.9903d
        "latitude below -90"       | -95.0d     | 0.0d
        "longitude above 180"      | 0.0d       | 185.0d
        "longitude far below -180" | 39.7392d   | -200.0d
        "longitude below -180"     | 0.0d       | -185.0d
        "NaN latitude"             | Double.NaN | -104.9903d
        "NaN longitude"            | 39.7392d   | Double.NaN
    }

    @Unroll
    def "RESOLVE | reads state #expectedState from region name #regionName when state code is missing"() {
        given: "a region covering the test point with null stateCode"
        createRegionWithMetadata(regionName, 1, null, timeZone, minLon, 30.0d, maxLon, 31.0d)

        when: "resolving coordinates inside that region"
        Optional<RegionCoordinateResolution> res = regionService.resolveCoordinates(30.5d, testLon)

        then: "the expected state name or code is resolved"
        verifyAll {
            res.isPresent()
            res.get().state() == expectedState
        }

        where:
        regionName               | timeZone          | minLon | maxLon | testLon || expectedState
        "Texas"                  | "America/Chicago" | -98.0d | -97.0d | -97.5d  || "TX"
        "CO State Region"        | "America/Denver"  | -96.0d | -95.0d | -95.5d  || "CO"
        "Custom Territory State" | "America/Denver"  | -94.0d | -93.0d | -93.5d  || "Custom Territory"
    }

    def "RESOLVE | returns empty when a covering region has a blank name and no metadata"() {
        given: "a region with blank name and null metadata"
        createRegionWithMetadata("   ", 1, null, null, -92.0d, 30.0d, -91.0d, 31.0d)

        when: "resolving coordinates inside that region"
        Optional<RegionCoordinateResolution> emptyRes = regionService.resolveCoordinates(30.5d, -91.5d)

        then: "empty optional is returned when all civic components are null"
        emptyRes.isEmpty()
    }

    @Unroll
    def "HIERARCHY | finds Colorado when searching by #scenario"() {
        when: "searching for the state"
        Optional<AdministrativeRegion> found = regionService.findState(query)

        then: "Colorado is returned"
        verifyAll {
            found.isPresent()
            found.get().stateCode() == "CO"
        }

        where:
        scenario                   | query
        "uppercase state code"     | "CO"
        "lowercase state code"     | "co"
        "full state name"          | "Colorado"
        "partial state name prefix"| "Colo"
    }

    @Unroll
    def "HIERARCHY | returns no state when searching with #scenario"() {
        when: "searching for an invalid or missing state"
        Optional<AdministrativeRegion> found = regionService.findState(query)

        then: "empty optional is returned"
        found.isEmpty()

        where:
        scenario            | query
        "non-existent code" | "ZZ"
        "blank string"      | "   "
        "null value"        | null
    }

    @Unroll
    def "HIERARCHY | lists all counties when state filter is #scenario"() {
        when: "querying counties with no state filter"
        List<AdministrativeRegion> counties = regionService.getCounties(stateFilter)

        then: "all level 2 counties are returned"
        verifyAll {
            !counties.isEmpty()
            counties.every { it.level() == 2 }
        }

        where:
        scenario       | stateFilter
        "null"         | null
        "blank spaces" | "   "
    }

    def "HIERARCHY | returns no counties for an unknown state"() {
        when: "querying counties with a non-existent state"
        List<AdministrativeRegion> noCounties = regionService.getCounties("ZZ")

        then: "empty list is returned"
        noCounties.isEmpty()
    }

    def "HIERARCHY | lists all cities in a state when no county is given"() {
        when: "querying cities for state CO directly"
        List<AdministrativeRegion> cities = regionService.getCities("CO", null)

        then: "cities for Colorado are returned"
        verifyAll {
            !cities.isEmpty()
            cities.every { it.level() == 3 && it.stateCode() == "CO" }
        }
    }

    @Unroll
    def "HIERARCHY | returns no cities when #scenario"() {
        when: "querying cities with an unknown state or county"
        List<AdministrativeRegion> cities = regionService.getCities(stateCode, countyName)

        then: "empty list is returned"
        cities.isEmpty()

        where:
        scenario             | stateCode | countyName
        "state is unknown"   | "ZZ"      | null
        "county is unknown"  | "CO"      | "Nonexistent County"
    }

    def "HIERARCHY | lists all cities when neither state nor county is given"() {
        when: "querying cities with null state and null county"
        List<AdministrativeRegion> allCities = regionService.getCities(null, null)

        then: "all level 3 cities are returned"
        verifyAll {
            !allCities.isEmpty()
            allCities.every { it.level() == 3 }
        }
    }

    @Unroll
    def "HIERARCHY | finds Denver cities when querying by #scenario"() {
        when: "querying cities"
        List<AdministrativeRegion> cities = regionService.getCities(stateCode, countyName)

        then: "Denver cities are returned"
        verifyAll {
            !cities.isEmpty()
            cities.any { it.name().contains("Denver") }
        }

        where:
        scenario                  | stateCode | countyName
        "county name without state" | null    | "Denver County"
        "county name prefix"        | "CO"    | "Denver"
    }

    def "HIERARCHY | handles county names ending with Region without error"() {
        given: "an existing state and a county ending with Region"
        AdministrativeRegion co = regionService.findState("CO").orElseThrow()
        AdministrativeRegion adamsCounty = dataBuilder.region().withName("Adams Region").withParent(co.id()).persist()
        track(adamsCounty)

        when: "querying cities with the stripped county name"
        List<AdministrativeRegion> normalizedMatchCities = regionService.getCities("CO", "Adams")

        then: "cities query returns successfully"
        normalizedMatchCities != null
    }

    def "HIERARCHY | returns no cities when county name does not match"() {
        when: "querying cities with a non-existent county"
        List<AdministrativeRegion> noCities = regionService.getCities("CO", "NonExistent")

        then: "empty list is returned"
        noCities.isEmpty()
    }

    def "GEOID | should find backfilled Colorado seed regions by geoid and leave downtown cores null"() {
        when: "looking up Colorado state, counties, and cities by geoid"
        Optional<AdministrativeRegion> colorado = regionRepository.findByGeoid("08")
        Optional<AdministrativeRegion> denverCounty = regionRepository.findByGeoid("08031")
        Optional<AdministrativeRegion> elPasoCounty = regionRepository.findByGeoid("08041")
        Optional<AdministrativeRegion> arapahoeCounty = regionRepository.findByGeoid("08005")
        Optional<AdministrativeRegion> denverCity = regionRepository.findByGeoid("0820000")
        Optional<AdministrativeRegion> downtownCore = regionRepository.findById(UUID.fromString("a0000000-0000-0000-0000-000000000004"))

        then: "expected seed rows have matching geoids and downtown core stays null"
        verifyAll {
            colorado.isPresent()
            colorado.get().name() == "Colorado State Region"
            denverCounty.isPresent()
            denverCounty.get().name() == "Denver County Region"
            elPasoCounty.isPresent()
            elPasoCounty.get().name() == "El Paso County Region"
            arapahoeCounty.isPresent()
            arapahoeCounty.get().name() == "Arapahoe County Region"
            denverCity.isPresent()
            denverCity.get().name() == "Denver City"
            downtownCore.isPresent()
            downtownCore.get().geoid() == null
        }
    }

    def "HIERARCHY | should exclude level 3 independent cities from getCounties"() {
        given: "a state and a level 3 independent city whose parentRegionId points directly to the state"
        AdministrativeRegion state = createRegionWithMetadata("Virginia State", 1, "VA", "America/New_York", -79.0, 36.5, -75.0, 39.5)
        Polygon countyPoly = spatialMappingService.toPolygon([
                new Coordinates(-78.0, 37.0),
                new Coordinates(-77.5, 37.0),
                new Coordinates(-77.5, 37.5),
                new Coordinates(-78.0, 37.5),
                new Coordinates(-78.0, 37.0)
        ])
        AdministrativeRegion realCounty = regionRepository.save(new AdministrativeRegion(
                null, "Henrico County", state.id(), countyPoly, 2, "US", "VA", "America/New_York", "51087"
        ))
        track(realCounty)

        Polygon cityPoly = spatialMappingService.toPolygon([
                new Coordinates(-77.6, 37.4),
                new Coordinates(-77.4, 37.4),
                new Coordinates(-77.4, 37.6),
                new Coordinates(-77.6, 37.6),
                new Coordinates(-77.6, 37.4)
        ])
        AdministrativeRegion independentCity = regionRepository.save(new AdministrativeRegion(
                null, "Richmond City", state.id(), cityPoly, 3, "US", "VA", "America/New_York", "5167000"
        ))
        track(independentCity)

        when: "querying counties for the state"
        List<AdministrativeRegion> counties = regionService.getCounties("VA")

        then: "only the level 2 county is returned and the level 3 independent city is excluded"
        verifyAll {
            counties.size() == 1
            counties[0].id() == realCounty.id()
            counties.every { it.level() == 2 }
            !counties.any { it.id() == independentCity.id() }
        }
    }

    def "HIERARCHY | should return multi-county city for every overlapping county"() {
        given: "a state with two adjacent counties"
        AdministrativeRegion state = createRegionWithMetadata("Test Multi County State", 1, "MC", "America/Denver", -106.0, 38.0, -104.0, 40.0)
        Polygon countyAPoly = spatialMappingService.toPolygon([
                new Coordinates(-105.5, 39.0),
                new Coordinates(-105.0, 39.0),
                new Coordinates(-105.0, 39.5),
                new Coordinates(-105.5, 39.5),
                new Coordinates(-105.5, 39.0)
        ])
        AdministrativeRegion countyA = regionRepository.save(new AdministrativeRegion(
                null, "Alpha County", state.id(), countyAPoly, 2, "US", "MC", "America/Denver", "99001"
        ))
        track(countyA)

        Polygon countyBPoly = spatialMappingService.toPolygon([
                new Coordinates(-105.0, 39.0),
                new Coordinates(-104.5, 39.0),
                new Coordinates(-104.5, 39.5),
                new Coordinates(-105.0, 39.5),
                new Coordinates(-105.0, 39.0)
        ])
        AdministrativeRegion countyB = regionRepository.save(new AdministrativeRegion(
                null, "Beta County", state.id(), countyBPoly, 2, "US", "MC", "America/Denver", "99003"
        ))
        track(countyB)

        and: "a city whose parentRegionId is Alpha County but whose geometry spans both Alpha and Beta"
        Polygon cityPoly = spatialMappingService.toPolygon([
                new Coordinates(-105.2, 39.1),
                new Coordinates(-104.8, 39.1),
                new Coordinates(-104.8, 39.3),
                new Coordinates(-105.2, 39.3),
                new Coordinates(-105.2, 39.1)
        ])
        AdministrativeRegion sharedCity = regionRepository.save(new AdministrativeRegion(
                null, "Splitville City", countyA.id(), cityPoly, 3, "US", "MC", "America/Denver", "9950000"
        ))
        track(sharedCity)

        when: "querying cities in Alpha County and Beta County"
        List<AdministrativeRegion> alphaCities = regionService.getCities("MC", "Alpha County")
        List<AdministrativeRegion> betaCities = regionService.getCities("MC", "Beta County")

        then: "Splitville City is returned for both counties"
        verifyAll {
            alphaCities.any { it.id() == sharedCity.id() }
            betaCities.any { it.id() == sharedCity.id() }
        }
    }

    @Unroll
    def "HIERARCHY | finds #regionName by lookup term #query"() {
        given: "a DC or US territory level 1 region"
        createRegionWithMetadata(regionName, 1, expectedCode, timeZone, minLon, minLat, maxLon, maxLat)

        expect: "findState resolves it to the expected state code"
        regionService.findState(query).map { it.stateCode() }.orElse(null) == expectedCode

        where:
        regionName                                   | expectedCode | timeZone              | minLon | minLat | maxLon | maxLat | query
        "District of Columbia"                       | "DC"         | "America/New_York"    | -77.1  | 38.8   | -76.9  | 39.0   | "DC"
        "District of Columbia"                       | "DC"         | "America/New_York"    | -77.1  | 38.8   | -76.9  | 39.0   | "District of Columbia"
        "Puerto Rico"                                | "PR"         | "America/Puerto_Rico" | -67.3  | 17.9   | -65.2  | 18.5   | "PR"
        "Puerto Rico"                                | "PR"         | "America/Puerto_Rico" | -67.3  | 17.9   | -65.2  | 18.5   | "Puerto Rico"
        "United States Virgin Islands"               | "VI"         | "America/St_Thomas"   | -65.1  | 17.6   | -64.5  | 18.4   | "VI"
        "United States Virgin Islands"               | "VI"         | "America/St_Thomas"   | -65.1  | 17.6   | -64.5  | 18.4   | "Virgin Islands"
        "United States Virgin Islands"               | "VI"         | "America/St_Thomas"   | -65.1  | 17.6   | -64.5  | 18.4   | "United States Virgin Islands"
        "Guam"                                       | "GU"         | "Pacific/Guam"        | 144.6  | 13.2   | 145.0  | 13.7   | "GU"
        "Guam"                                       | "GU"         | "Pacific/Guam"        | 144.6  | 13.2   | 145.0  | 13.7   | "Guam"
        "American Samoa"                             | "AS"         | "Pacific/Pago_Pago"   | -170.9 | -14.4  | -170.5 | -14.2  | "AS"
        "American Samoa"                             | "AS"         | "Pacific/Pago_Pago"   | -170.9 | -14.4  | -170.5 | -14.2  | "American Samoa"
        "Commonwealth of the Northern Mariana Islands" | "MP"       | "Pacific/Saipan"      | 145.1  | 14.1   | 145.8  | 15.3   | "MP"
        "Commonwealth of the Northern Mariana Islands" | "MP"       | "Pacific/Saipan"      | 145.1  | 14.1   | 145.8  | 15.3   | "Northern Mariana Islands"
    }

    def "RESOLVE | resolves territory state code from name when state code is missing"() {
        given: "a territory region without a state code"
        createRegionWithMetadata("Puerto Rico", 1, null, "America/Puerto_Rico", -60.0, 10.0, -59.0, 11.0)

        when: "resolving coordinates inside that region"
        Optional<RegionCoordinateResolution> prRes = regionService.resolveCoordinates(10.5d, -59.5d)

        then: "Puerto Rico state code PR is resolved"
        verifyAll {
            prRes.isPresent()
            prRes.get().state() == "PR"
        }
    }

    private DefaultAdministrativeRegionService buildUnitService(
            AdministrativeRegionRepository regionRepo,
            UserRepository userRepo,
            ObjectMapper mapper
    ) {
        return new DefaultAdministrativeRegionService(
                regionRepo,
                userRepo,
                spatialMappingService,
                Mock(AuditService),
                Mock(JdbcOperations),
                mapper
        )
    }

    def "RESOLVE | should ignore blank city, county, and timezone names when resolving coordinates"() {
        given: "regions with blank city, county, and timezone names under a valid state"
        createRegionWithMetadata("Test Blank State", 1, "TB", "   ", -85.0, 34.0, -84.0, 35.0)
        createRegionWithMetadata("   ", 2, "TB", "   ", -84.9, 34.6, -84.1, 34.9)
        createRegionWithMetadata("   ", 3, "TB", "   ", -84.8, 34.65, -84.2, 34.85)

        when: "resolving coordinates inside the blank-metadata regions"
        Optional<RegionCoordinateResolution> res = regionService.resolveCoordinates(34.75d, -84.5d)

        then: "only the state code is populated and blank fields resolve to null"
        verifyAll {
            res.isPresent()
            res.get().state() == "TB"
            res.get().county() == null
            res.get().city() == null
            res.get().timeZone() == null
        }
    }

    def "HIERARCHY | should find a state by single-letter prefix"() {
        when: "finding a state using a single-letter query"
        Optional<AdministrativeRegion> bySingleChar = regionService.findState("C")

        then: "a matching state is returned"
        bySingleChar.isPresent()
    }

    def "HIERARCHY | should match normalized county prefix when state filter is blank"() {
        given: "a state, a county with leading spaces, and a city inside that county"
        AdministrativeRegion state = createRegionWithMetadata("Test Blank State", 1, "TB", "   ", -85.0, 34.0, -84.0, 35.0)
        Polygon countyPoly = spatialMappingService.toPolygon([
                new Coordinates(-84.5, 34.1),
                new Coordinates(-84.3, 34.1),
                new Coordinates(-84.3, 34.3),
                new Coordinates(-84.5, 34.3),
                new Coordinates(-84.5, 34.1)
        ])
        AdministrativeRegion spacedCounty = regionRepository.save(new AdministrativeRegion(
                null, "  Spacedville Region", state.id(), countyPoly, 2, "US", "TB", "America/New_York", "98001"
        ))
        track(spacedCounty)
        AdministrativeRegion cityInSpaced = regionRepository.save(new AdministrativeRegion(
                null, "Spaced City", spacedCounty.id(), countyPoly, 3, "US", "TB", "America/New_York", "9850001"
        ))
        track(cityInSpaced)

        when: "querying cities with a blank state and a normalized county prefix"
        List<AdministrativeRegion> cities = regionService.getCities("   ", "Spaced")

        then: "the city inside the matching county is returned"
        cities.any { it.id() == cityInSpaced.id() }
    }

    def "UNIT | should return 404 when deleteAdministrativeRegion removes zero rows"() {
        given: "a region that disappears before delete executes"
        AdministrativeRegionRepository mockRegionRepo = Mock(AdministrativeRegionRepository)
        UserRepository mockUserRepo = Mock(UserRepository)
        ObjectMapper mockMapper = Mock(ObjectMapper)
        DefaultAdministrativeRegionService unitService = buildUnitService(mockRegionRepo, mockUserRepo, mockMapper)

        UUID actorId = UUID.randomUUID()
        UUID regionId = UUID.randomUUID()
        User admin = dataBuilder.user().asAdmin().build().withId(actorId)
        AdministrativeRegion region = new AdministrativeRegion(regionId, "Unit Region", null, null, 1, "US", "CO", null, null)

        mockUserRepo.findById(actorId) >> Optional.of(admin)
        mockRegionRepo.findById(regionId) >> Optional.of(region)
        mockMapper.writeValueAsString(_) >> '{"deletedName":"Unit Region"}'
        mockRegionRepo.removeById(regionId) >> 0L

        when: "deleting the region"
        unitService.deleteAdministrativeRegion(regionId, actorId)

        then: "404 Not Found is thrown"
        HttpStatusException delEx = thrown()
        delEx.status == HttpStatus.NOT_FOUND
    }

    def "UNIT | should return 500 when deleteAdministrativeRegion metadata serialization fails"() {
        given: "an object mapper that throws IOException when writing metadata"
        AdministrativeRegionRepository mockRegionRepo = Mock(AdministrativeRegionRepository)
        UserRepository mockUserRepo = Mock(UserRepository)
        ObjectMapper failingMapper = Mock(ObjectMapper) {
            writeValueAsString(_) >> { throw new IOException("JSON write failed") }
        }
        DefaultAdministrativeRegionService unitService = buildUnitService(mockRegionRepo, mockUserRepo, failingMapper)

        UUID actorId = UUID.randomUUID()
        UUID regionId = UUID.randomUUID()
        User admin = dataBuilder.user().asAdmin().build().withId(actorId)
        AdministrativeRegion region = new AdministrativeRegion(regionId, "Unit Region", null, null, 1, "US", "CO", null, null)

        mockUserRepo.findById(actorId) >> Optional.of(admin)
        mockRegionRepo.findById(regionId) >> Optional.of(region)

        when: "deleting the region"
        unitService.deleteAdministrativeRegion(regionId, actorId)

        then: "500 Internal Server Error is thrown"
        HttpStatusException jsonEx = thrown()
        jsonEx.status == HttpStatus.INTERNAL_SERVER_ERROR
    }

    @Unroll
    def "UNIT | should return empty optional from resolveCoordinates for #scenario"() {
        given: "a repository returning empty or blank covering regions"
        AdministrativeRegionRepository mockRegionRepo = Mock(AdministrativeRegionRepository)
        DefaultAdministrativeRegionService unitService = buildUnitService(mockRegionRepo, Mock(UserRepository), Mock(ObjectMapper))
        mockRegionRepo.findCoveringRegions(lat, -105.0d) >> coveringRegions

        when: "resolving coordinates"
        Optional<RegionCoordinateResolution> res = unitService.resolveCoordinates(lat, -105.0d)

        then: "empty optional is returned"
        res.isEmpty()

        where:
        scenario                   | lat   | coveringRegions
        "null covering list"       | 39.0d | null
        "regions with blank names" | 40.0d | [
                new CoveringRegion(UUID.randomUUID(), null, null, "US", null, null),
                new CoveringRegion(UUID.randomUUID(), null, 3, "US", null, null),
                new CoveringRegion(UUID.randomUUID(), "   ", 3, "US", null, null),
                new CoveringRegion(UUID.randomUUID(), null, 2, "US", null, null),
                new CoveringRegion(UUID.randomUUID(), "  ", 2, "US", null, null),
                new CoveringRegion(UUID.randomUUID(), null, 1, "US", "  ", null),
                new CoveringRegion(UUID.randomUUID(), "  ", 1, "US", "  ", "  ")
        ]
    }

    def "UNIT | should resolve timezone when covering region only has a timezone"() {
        given: "a covering region with only a timezone populated"
        AdministrativeRegionRepository mockRegionRepo = Mock(AdministrativeRegionRepository)
        DefaultAdministrativeRegionService unitService = buildUnitService(mockRegionRepo, Mock(UserRepository), Mock(ObjectMapper))
        mockRegionRepo.findCoveringRegions(41.0d, -105.0d) >> [
                new CoveringRegion(UUID.randomUUID(), null, 1, "US", null, "America/Denver")
        ]

        when: "resolving coordinates"
        Optional<RegionCoordinateResolution> tzOnlyRes = unitService.resolveCoordinates(41.0d, -105.0d)

        then: "the timezone is returned"
        verifyAll {
            tzOnlyRes.isPresent()
            tzOnlyRes.get().timeZone() == "America/Denver"
        }
    }

    def "UNIT | should skip counties with null names in getCities"() {
        given: "a level-2 region with a null name"
        AdministrativeRegionRepository mockRegionRepo = Mock(AdministrativeRegionRepository)
        DefaultAdministrativeRegionService unitService = buildUnitService(mockRegionRepo, Mock(UserRepository), Mock(ObjectMapper))
        mockRegionRepo.findByLevelOrderByNameAsc(2) >> [
                new AdministrativeRegion(UUID.randomUUID(), null, null, null, 2, "US", "CO", null, null)
        ]

        when: "querying cities by county name"
        List<AdministrativeRegion> cities = unitService.getCities(null, "Denver")

        then: "null county names are skipped and an empty list is returned"
        cities.isEmpty()
    }

    def "UNIT | should return empty list when getCities receives blank filters"() {
        given: "no level-3 cities in the repository"
        AdministrativeRegionRepository mockRegionRepo = Mock(AdministrativeRegionRepository)
        DefaultAdministrativeRegionService unitService = buildUnitService(mockRegionRepo, Mock(UserRepository), Mock(ObjectMapper))
        mockRegionRepo.findByLevelOrderByNameAsc(3) >> []

        when: "querying cities with blank state and county filters"
        List<AdministrativeRegion> cities = unitService.getCities("   ", "   ")

        then: "an empty list is returned"
        cities.isEmpty()
    }

    @Unroll
    def "UNIT | should find state using fallback scan by #lookupType"() {
        given: "a direct code lookup miss and a level-1 state list containing null and matching names"
        AdministrativeRegionRepository mockRegionRepo = Mock(AdministrativeRegionRepository)
        DefaultAdministrativeRegionService unitService = buildUnitService(mockRegionRepo, Mock(UserRepository), Mock(ObjectMapper))
        mockRegionRepo.findFirstByLevelAndStateCodeIgnoreCase(1, query) >> Optional.empty()
        mockRegionRepo.findByLevelOrderByNameAsc(1) >> [
                new AdministrativeRegion(UUID.randomUUID(), null, null, null, 1, "US", null, null, null),
                new AdministrativeRegion(UUID.randomUUID(), "Fallback State", null, null, 1, "US", "ZZ", null, null)
        ]

        when: "finding the state"
        Optional<AdministrativeRegion> result = unitService.findState(query)

        then: "the matching state is found"
        result.isPresent()

        where:
        lookupType   | query
        "state code" | "ZZ"
        "state name" | "Fallback State"
    }
}
