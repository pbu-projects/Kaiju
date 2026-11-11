package lol.pbu.kaiju.service

import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.controller.BaseControllerSpec
import lol.pbu.kaiju.domain.AdministrativeRegion
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.dto.CoordinateDto
import lol.pbu.kaiju.dto.CreateAdministrativeRegionCommand
import lol.pbu.kaiju.dto.IngestBoundaryResponse
import lol.pbu.kaiju.dto.RegionCoordinateResolution
import lol.pbu.kaiju.dto.UpdateAdministrativeRegionCommand
import lol.pbu.kaiju.model.RegionAuditAction
import lol.pbu.kaiju.repository.AdministrativeRegionAuditLogRepository
import lol.pbu.kaiju.repository.AdministrativeRegionRepository
import lol.pbu.kaiju.util.SpatialMappingService
import org.locationtech.jts.geom.Polygon

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
        List<CoordinateDto> coords = [
                new CoordinateDto(minLon, minLat),
                new CoordinateDto(maxLon, minLat),
                new CoordinateDto(maxLon, maxLat),
                new CoordinateDto(minLon, maxLat),
                new CoordinateDto(minLon, minLat)
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
                timeZone
        )
        AdministrativeRegion saved = regionRepository.save(region)
        track(saved)
        return saved
    }

    private List<CoordinateDto> validCoordinates() {
        [
                new CoordinateDto(-105.0, 39.0),
                new CoordinateDto(-104.0, 39.0),
                new CoordinateDto(-104.0, 40.0),
                new CoordinateDto(-105.0, 40.0),
                new CoordinateDto(-105.0, 39.0)
        ]
    }

    private List<CoordinateDto> bowtieCoordinates() {
        // Self-intersecting figure-8 bowtie polygon (topologically invalid)
        [
                new CoordinateDto(0.0, 0.0),
                new CoordinateDto(2.0, 2.0),
                new CoordinateDto(2.0, 0.0),
                new CoordinateDto(0.0, 2.0),
                new CoordinateDto(0.0, 0.0)
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
        }
    }

    def "QUERY | should get administrative regions page and find by id"() {
        given: "an existing administrative region"
        def createCmd = new CreateAdministrativeRegionCommand("Query Region", null, validCoordinates())
        AdministrativeRegion region = regionService.createAdministrativeRegion(createCmd, testAdminUser.id())
        track(region)

        when: "querying regions with a pageable"
        def page = regionService.getAdministrativeRegions(CursoredPageable.from(10, Sort.UNSORTED))

        then: "regions page is returned with content"
        verifyAll(page) {
            page != null
            !content.isEmpty()
            content.size() <= 10
        }

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
        given: "a valid create command"
        def command = new CreateAdministrativeRegionCommand("Metro North", null, validCoordinates())

        when: "the region is created via the service"
        AdministrativeRegion region = regionService.createAdministrativeRegion(command, testAdminUser.id())
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
        given: "a command referencing a non-existent parent ID"
        UUID missingParentId = UUID.randomUUID()
        def command = new CreateAdministrativeRegionCommand("Child Region", missingParentId, validCoordinates())

        when: "attempting to create the region"
        regionService.createAdministrativeRegion(command, testAdminUser.id())

        then: "HTTP 400 Bad Request is thrown"
        def e = thrown(HttpStatusException)
        verifyAll {
            e.status == HttpStatus.BAD_REQUEST
        }
    }

    def "CREATE | should fail when geometry is topologically invalid"() {
        given: "a command with a self-intersecting polygon"
        def command = new CreateAdministrativeRegionCommand("Invalid Region", null, bowtieCoordinates())

        when: "attempting to create the region"
        regionService.createAdministrativeRegion(command, testAdminUser.id())

        then: "HTTP 422 Unprocessable Entity is thrown"
        def e = thrown(HttpStatusException)
        verifyAll {
            e.status == HttpStatus.UNPROCESSABLE_ENTITY
        }
    }

    def "CREATE | should fail when actor user does not exist"() {
        given: "a non-existent actor UUID"
        UUID missingUserId = UUID.randomUUID()
        def command = new CreateAdministrativeRegionCommand("Valid Region", null, validCoordinates())

        when: "attempting to create the region"
        regionService.createAdministrativeRegion(command, missingUserId)

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

        and: "a command to create a child region referencing the parent"
        def command = new CreateAdministrativeRegionCommand("Child With Level Fallback", parent.id(), validCoordinates())

        when: "creating the child region"
        AdministrativeRegion child = regionService.createAdministrativeRegion(command, testAdminUser.id())
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
        def createCmd = new CreateAdministrativeRegionCommand("Original Name", null, validCoordinates())
        AdministrativeRegion initial = regionService.createAdministrativeRegion(createCmd, testAdminUser.id())
        track(initial)

        and: "an update command"
        def updateCmd = new UpdateAdministrativeRegionCommand("Renamed Region", null, validCoordinates())

        when: "updating the administrative region"
        AdministrativeRegion updated = regionService.updateAdministrativeRegion(initial.id(), updateCmd, testAdminUser.id())

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
        def createCmd = new CreateAdministrativeRegionCommand("Self Parent Region", null, validCoordinates())
        AdministrativeRegion region = regionService.createAdministrativeRegion(createCmd, testAdminUser.id())
        track(region)

        and: "an update command setting parentRegionId to its own ID"
        def updateCmd = new UpdateAdministrativeRegionCommand("Self Parent Region", region.id(), validCoordinates())

        when: "attempting to update the region"
        regionService.updateAdministrativeRegion(region.id(), updateCmd, testAdminUser.id())

        then: "HTTP 400 Bad Request is thrown"
        def e = thrown(HttpStatusException)
        verifyAll {
            e.status == HttpStatus.BAD_REQUEST
        }
    }

    def "UPDATE | should reject setting a non-existent parent administrative region"() {
        given: "an existing administrative region"
        def createCmd = new CreateAdministrativeRegionCommand("Region", null, validCoordinates())
        AdministrativeRegion region = regionService.createAdministrativeRegion(createCmd, testAdminUser.id())
        track(region)

        and: "an update command setting a non-existent parent ID"
        def updateCmd = new UpdateAdministrativeRegionCommand("Region", UUID.randomUUID(), validCoordinates())

        when: "attempting to update the region"
        regionService.updateAdministrativeRegion(region.id(), updateCmd, testAdminUser.id())

        then: "HTTP 400 Bad Request is thrown"
        def e = thrown(HttpStatusException)
        verifyAll {
            e.status == HttpStatus.BAD_REQUEST
        }
    }

    def "UPDATE | should return 404 when updating non-existent region"() {
        given: "a non-existent region ID"
        UUID missingId = UUID.randomUUID()
        def updateCmd = new UpdateAdministrativeRegionCommand("New Name", null, validCoordinates())

        when: "attempting to update"
        regionService.updateAdministrativeRegion(missingId, updateCmd, testAdminUser.id())

        then: "HTTP 404 Not Found is thrown"
        def e = thrown(HttpStatusException)
        verifyAll {
            e.status == HttpStatus.NOT_FOUND
        }
    }

    def "UPDATE | should fail when updating with topologically invalid geometry"() {
        given: "an existing administrative region"
        def createCmd = new CreateAdministrativeRegionCommand("Valid Region Before Update", null, validCoordinates())
        AdministrativeRegion initial = regionService.createAdministrativeRegion(createCmd, testAdminUser.id())
        track(initial)

        and: "an update command with a self-intersecting polygon"
        def updateCmd = new UpdateAdministrativeRegionCommand("Updated Region", null, bowtieCoordinates())

        when: "attempting to update the region"
        regionService.updateAdministrativeRegion(initial.id(), updateCmd, testAdminUser.id())

        then: "HTTP 422 Unprocessable Entity is thrown"
        def e = thrown(HttpStatusException)
        verifyAll {
            e.status == HttpStatus.UNPROCESSABLE_ENTITY
        }
    }

    def "DELETE | should delete an administrative region and preserve immutable audit log"() {
        given: "an existing administrative region"
        def createCmd = new CreateAdministrativeRegionCommand("Region To Delete", null, validCoordinates())
        AdministrativeRegion region = regionService.createAdministrativeRegion(createCmd, testAdminUser.id())

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
        def createCmd = new CreateAdministrativeRegionCommand("Boundary Test Region", null, validCoordinates())
        AdministrativeRegion region = regionService.createAdministrativeRegion(createCmd, testAdminUser.id())
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

    def "RESOLVE | should resolve coordinates to administrative regions and timezone"() {
        when: "resolving coordinates for Denver city boundary"
        Optional<RegionCoordinateResolution> result = regionService.resolveCoordinates(39.7392d, -104.9903d)

        then: "civic boundaries and timezone are resolved with full city name"
        verifyAll {
            result.isPresent()
            result.get().state() == "CO"
            result.get().county() == "Denver County"
            result.get().city() == "Denver City"
            result.get().timeZone() == "America/Denver"
        }

        when: "resolving coordinates inside downtown core"
        Optional<RegionCoordinateResolution> downtown = regionService.resolveCoordinates(39.7450d, -104.9920d)

        then: "downtown core suffix is stripped to canonical municipality name"
        verifyAll {
            downtown.isPresent()
            downtown.get().state() == "CO"
            downtown.get().county() == "Denver County"
            downtown.get().city() == "Denver"
            downtown.get().timeZone() == "America/Denver"
        }
    }

    def "RESOLVE | should return empty optional when coordinates are outside coverage"() {
        when: "resolving coordinates outside covered regions"
        Optional<RegionCoordinateResolution> result = regionService.resolveCoordinates(89.0d, -140.0d)

        then: "empty optional is returned"
        verifyAll {
            result.isEmpty()
        }
    }

    def "HIERARCHY | should query states, counties, and cities"() {
        when: "querying all states"
        List<AdministrativeRegion> states = regionService.getStates()

        then: "Colorado is present"
        verifyAll {
            !states.isEmpty()
            states.any { it.stateCode() == "CO" || it.name().contains("Colorado") }
        }

        when: "querying counties for Colorado"
        List<AdministrativeRegion> counties = regionService.getCounties("CO")

        then: "Denver County is present"
        verifyAll {
            !counties.isEmpty()
            counties.any { it.name().contains("Denver County") }
        }

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
        def createCmd = new CreateAdministrativeRegionCommand("Preserved County", state.id(), validCoordinates())
        AdministrativeRegion created = regionService.createAdministrativeRegion(createCmd, testAdminUser.id())
        track(created)

        verifyAll {
            created.level() == 2
            created.countryCode() == "US"
            created.stateCode() == "CO"
            created.timeZone() == "America/Denver"
        }

        when: "updating the region name and coordinates"
        def updateCmd = new UpdateAdministrativeRegionCommand("Preserved County Renamed", state.id(), validCoordinates())
        AdministrativeRegion updated = regionService.updateAdministrativeRegion(created.id(), updateCmd, testAdminUser.id())

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

    def "RESOLVE | should return empty optional for invalid or out of bounds coordinates"() {
        when: "resolving with latitude above 90"
        Optional<RegionCoordinateResolution> outOfBoundsLat = regionService.resolveCoordinates(95.0d, -104.9903d)

        then: "empty optional is returned without exception"
        verifyAll {
            outOfBoundsLat.isEmpty()
        }

        when: "resolving with latitude below -90"
        Optional<RegionCoordinateResolution> lowLat = regionService.resolveCoordinates(-95.0d, 0.0d)

        then: "empty optional is returned"
        verifyAll {
            lowLat.isEmpty()
        }

        when: "resolving with longitude above 180"
        Optional<RegionCoordinateResolution> highLon = regionService.resolveCoordinates(0.0d, 185.0d)

        then: "empty optional is returned"
        verifyAll {
            highLon.isEmpty()
        }

        when: "resolving with longitude below -180"
        Optional<RegionCoordinateResolution> outOfBoundsLon = regionService.resolveCoordinates(39.7392d, -200.0d)

        then: "empty optional is returned without exception"
        verifyAll {
            outOfBoundsLon.isEmpty()
        }

        when: "resolving with longitude below -180 boundary"
        Optional<RegionCoordinateResolution> lowLon = regionService.resolveCoordinates(0.0d, -185.0d)

        then: "empty optional is returned"
        verifyAll {
            lowLon.isEmpty()
        }

        when: "resolving with NaN latitude"
        Optional<RegionCoordinateResolution> nanLat = regionService.resolveCoordinates(Double.NaN, -104.9903d)

        then: "empty optional is returned without exception"
        verifyAll {
            nanLat.isEmpty()
        }

        when: "resolving with NaN longitude"
        Optional<RegionCoordinateResolution> nanLon = regionService.resolveCoordinates(39.7392d, Double.NaN)

        then: "empty optional is returned without exception"
        verifyAll {
            nanLon.isEmpty()
        }
    }

    def "RESOLVE | should normalize state from region name when stateCode is null"() {
        given: "regions covering coordinates with null stateCode"
        createRegionWithMetadata("Texas", 1, null, "America/Chicago", -98.0, 30.0, -97.0, 31.0)
        createRegionWithMetadata("CO State Region", 1, null, "America/Denver", -96.0, 30.0, -95.0, 31.0)
        createRegionWithMetadata("Custom Territory State", 1, null, "America/Denver", -94.0, 30.0, -93.0, 31.0)
        createRegionWithMetadata("   ", 1, null, null, -92.0, 30.0, -91.0, 31.0)

        when: "resolving coordinates covering state name lookup"
        Optional<RegionCoordinateResolution> txRes = regionService.resolveCoordinates(30.5d, -97.5d)

        then: "state code TX is resolved from Texas name"
        verifyAll {
            txRes.isPresent()
            txRes.get().state() == "TX"
        }

        when: "resolving coordinates covering two-letter prefix state name"
        Optional<RegionCoordinateResolution> coRes = regionService.resolveCoordinates(30.5d, -95.5d)

        then: "state code CO is resolved"
        verifyAll {
            coRes.isPresent()
            coRes.get().state() == "CO"
        }

        when: "resolving coordinates covering unknown state name"
        Optional<RegionCoordinateResolution> customRes = regionService.resolveCoordinates(30.5d, -93.5d)

        then: "stripped name is resolved as state"
        verifyAll {
            customRes.isPresent()
            customRes.get().state() == "Custom Territory"
        }

        when: "resolving coordinates covering region with blank name and null metadata"
        Optional<RegionCoordinateResolution> emptyRes = regionService.resolveCoordinates(30.5d, -91.5d)

        then: "empty optional is returned when all civic components are null"
        verifyAll {
            emptyRes.isEmpty()
        }
    }

    def "HIERARCHY | should find states with codes, names, or prefixes"() {
        when: "searching by two-letter state code"
        Optional<AdministrativeRegion> byCode = regionService.findState("CO")

        then: "state is found"
        verifyAll {
            byCode.isPresent()
            byCode.get().stateCode() == "CO"
        }

        when: "searching by lowercase two-letter state code"
        Optional<AdministrativeRegion> byLowerCode = regionService.findState("co")

        then: "state is found"
        verifyAll {
            byLowerCode.isPresent()
            byLowerCode.get().stateCode() == "CO"
        }

        when: "searching by state name"
        Optional<AdministrativeRegion> byName = regionService.findState("Colorado")

        then: "state is found"
        verifyAll {
            byName.isPresent()
            byName.get().stateCode() == "CO"
        }

        when: "searching by state name prefix"
        Optional<AdministrativeRegion> byPrefix = regionService.findState("Colo")

        then: "state is found"
        verifyAll {
            byPrefix.isPresent()
            byPrefix.get().stateCode() == "CO"
        }

        when: "searching by non-existent state"
        Optional<AdministrativeRegion> notFound = regionService.findState("ZZ")

        then: "empty optional is returned"
        verifyAll {
            notFound.isEmpty()
        }

        when: "searching with blank or null state"
        Optional<AdministrativeRegion> blank = regionService.findState("   ")
        Optional<AdministrativeRegion> nullState = regionService.findState(null)

        then: "empty optionals are returned"
        verifyAll {
            blank.isEmpty()
            nullState.isEmpty()
        }
    }

    def "HIERARCHY | should get counties with null or blank state"() {
        when: "querying counties with null state"
        List<AdministrativeRegion> nullStateCounties = regionService.getCounties(null)

        then: "all level 2 counties are returned"
        verifyAll {
            !nullStateCounties.isEmpty()
            nullStateCounties.every { it.level() == 2 }
        }

        when: "querying counties with blank state"
        List<AdministrativeRegion> blankStateCounties = regionService.getCounties("   ")

        then: "all level 2 counties are returned"
        verifyAll {
            !blankStateCounties.isEmpty()
            blankStateCounties.every { it.level() == 2 }
        }

        when: "querying counties with non-existent state"
        List<AdministrativeRegion> noCounties = regionService.getCounties("ZZ")

        then: "empty list is returned"
        verifyAll {
            noCounties.isEmpty()
        }
    }

    def "HIERARCHY | should get cities for a state in a single query and handle edge cases"() {
        when: "querying cities for state CO directly"
        List<AdministrativeRegion> cities = regionService.getCities("CO", null)

        then: "cities for Colorado are returned"
        verifyAll {
            !cities.isEmpty()
            cities.every { it.level() == 3 && it.stateCode() == "CO" }
        }

        when: "querying cities for a non-existent state"
        List<AdministrativeRegion> noCities = regionService.getCities("ZZ", null)

        then: "empty list is returned"
        verifyAll {
            noCities.isEmpty()
        }

        when: "querying cities for a non-existent county"
        List<AdministrativeRegion> noCountyCities = regionService.getCities("CO", "Nonexistent County")

        then: "empty list is returned"
        verifyAll {
            noCountyCities.isEmpty()
        }
    }

    def "HIERARCHY | should get cities with null arguments and matching rules"() {
        given: "an existing state"
        AdministrativeRegion co = regionService.findState("CO").orElseThrow()

        and: "a county with Region suffix to test normalized candidate name matching"
        AdministrativeRegion adamsCounty = dataBuilder.region().withName("Adams Region").withParent(co.id()).persist()
        track(adamsCounty)

        when: "querying cities with null state and null county"
        List<AdministrativeRegion> allCities = regionService.getCities(null, null)

        then: "all level 3 cities are returned"
        verifyAll {
            !allCities.isEmpty()
            allCities.every { it.level() == 3 }
        }

        when: "querying cities with null state and county name fallback"
        List<AdministrativeRegion> denverFallbackCities = regionService.getCities(null, "Denver County")

        then: "cities for Denver County are returned via fallback"
        verifyAll {
            !denverFallbackCities.isEmpty()
            denverFallbackCities.any { it.name().contains("Denver") }
        }

        when: "querying cities with prefix match on county"
        List<AdministrativeRegion> prefixMatchCities = regionService.getCities("CO", "Denver")

        then: "cities matching prefix are returned"
        verifyAll {
            !prefixMatchCities.isEmpty()
            prefixMatchCities.any { it.name().contains("Denver") }
        }

        when: "querying cities with normalized county name matching suffix stripped"
        List<AdministrativeRegion> normalizedMatchCities = regionService.getCities("CO", "Adams")

        then: "cities query returns successfully"
        verifyAll {
            normalizedMatchCities != null
        }

        when: "querying cities with non-existent county"
        List<AdministrativeRegion> noCities = regionService.getCities("CO", "NonExistent")

        then: "empty list is returned"
        verifyAll {
            noCities.isEmpty()
        }
    }
}
