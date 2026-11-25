package lol.pbu.kaiju.controller

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.uri.UriBuilder
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.dto.CreateLocationRequest
import lol.pbu.kaiju.dto.UpdateLocationRequest
import lol.pbu.kaiju.fixtures.TestPersonas
import lol.pbu.kaiju.model.ProjectStatus
import lol.pbu.kaiju.repository.LocationRepository
import org.locationtech.jts.geom.Coordinate
import spock.lang.Unroll

import java.time.OffsetDateTime
import java.time.ZoneOffset

@MicronautTest(transactional = false)
class SearchAndDiscoveryEdgeCasesSpec extends BaseControllerSpec {

    @Inject
    LocationRepository locationRepository

    private Map searchOrganizationsByName(String name, boolean exact = false) {
        String uri = UriBuilder.of("/organizations/search-by-name")
                .queryParam("name", name)
                .queryParam("exact", exact)
                .build()
                .toString()
        return client.exchange(asGlobalAdmin(HttpRequest.GET(uri)), Map).body()
    }

    private Map searchOrganizationsByLocation(double longitude, double latitude, double radiusMeters) {
        String uri = UriBuilder.of("/organizations/search-by-location")
                .queryParam("longitude", longitude)
                .queryParam("latitude", latitude)
                .queryParam("radiusMeters", radiusMeters)
                .build()
                .toString()
        return client.exchange(asGlobalAdmin(HttpRequest.GET(uri)), Map).body()
    }

    private Map searchOrganizationsByRegion(UUID regionId) {
        String uri = UriBuilder.of("/organizations/search-by-region")
                .queryParam("regionId", regionId)
                .build()
                .toString()
        return client.exchange(asGlobalAdmin(HttpRequest.GET(uri)), Map).body()
    }

    def "quoted exact search finds only the exact org while typo search still finds it and blank search returns empty"() {
        given: "a verified public branch org with a similar name to National Relief Network"
        dataBuilder.organization()
                .withName("National Relief Network - Community Branch")
                .asPublic()
                .verified()
                .persist()

        when: "searching with quotes, with a typo, and with blank text"
        Map quotedResult = searchOrganizationsByName('"National Relief Network"')
        Map typoResult = searchOrganizationsByName("Nationl Relief")
        Map blankResult = searchOrganizationsByName("   ")

        then: "quoted search returns only the exact org, typo search finds it, and blank search returns empty"
        List<Map> quotedItems = (quotedResult.content as List<Map>) ?: []
        List<Map> typoItems = (typoResult.content as List<Map>) ?: []
        List<Map> blankItems = (blankResult.content as List<Map>) ?: []

        verifyAll {
            quotedItems.size() == 1
            quotedItems[0].id == TestPersonas.NATIONAL_RELIEF_NETWORK_ID.toString()
            quotedItems[0].name == "National Relief Network"

            !typoItems.isEmpty()
            typoItems[0].id == TestPersonas.NATIONAL_RELIEF_NETWORK_ID.toString()
            typoItems[0].name == "National Relief Network"

            blankItems.isEmpty()
            blankResult.totalSize == -1
        }
    }

    def "location and region searches skip expired and private organizations"() {
        given: "an isolated location and region with valid, expired, and private organizations"
        double lon = -116.2023
        double lat = 43.6150

        Location sharedLoc = dataBuilder.location()
                .withName("Boise Outreach Hub")
                .withAddress("100 Capitol Blvd", "Boise", "ID", "83702", "US")
                .atPoint(lon, lat)
                .persist()

        def region = dataBuilder.region()
                .withName("Idaho Search Test Region")
                .withGeometry(dataBuilder.geometryFactory.createPolygon([
                        new Coordinate(-116.5, 43.4),
                        new Coordinate(-116.0, 43.4),
                        new Coordinate(-116.0, 43.8),
                        new Coordinate(-116.5, 43.8),
                        new Coordinate(-116.5, 43.4)
                ] as Coordinate[]))
                .persist()

        def validOrg = dataBuilder.organization()
                .withName("Active Public Relief Org")
                .asPublic()
                .verified()
                .withVerificationExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(30))
                .withLocation(sharedLoc)
                .persist()

        def expiredOrg = dataBuilder.organization()
                .withName("Expired Verification Relief Org")
                .asPublic()
                .verified()
                .withVerificationExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1))
                .withLocation(sharedLoc)
                .persist()

        def privateOrg = dataBuilder.organization()
                .withName("Private Hidden Relief Org")
                .asPrivate()
                .verified()
                .withVerificationExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(30))
                .withLocation(sharedLoc)
                .persist()

        dataBuilder.linkOrganizationToRegion(validOrg, region)
        dataBuilder.linkOrganizationToRegion(expiredOrg, region)
        dataBuilder.linkOrganizationToRegion(privateOrg, region)

        when: "searching by location and by region"
        Map locationResult = searchOrganizationsByLocation(lon, lat, 5000)
        Map regionResult = searchOrganizationsByRegion(region.id())

        then: "only the public non-expired organization is returned"
        List<String> locationIds = ((locationResult.content as List<Map>) ?: []).collect { it.id as String }
        List<String> regionIds = ((regionResult.content as List<Map>) ?: []).collect { it.id as String }

        verifyAll {
            locationIds == [validOrg.id().toString()]
            !locationIds.contains(expiredOrg.id().toString())
            !locationIds.contains(privateOrg.id().toString())

            regionIds == [validOrg.id().toString()]
            !regionIds.contains(expiredOrg.id().toString())
            !regionIds.contains(privateOrg.id().toString())
        }
    }

    def "projects page returns an HTML fragment with HX-Request header and a full page without it"() {
        when: "requesting GET /projects with and without HX-Request: true"
        HttpResponse<String> fragmentResponse = client.exchange(
                HttpRequest.GET("/projects?q=food")
                        .accept(MediaType.TEXT_HTML)
                        .header("HX-Request", "true"),
                String
        )
        HttpResponse<String> fullPageResponse = client.exchange(
                HttpRequest.GET("/projects?q=food")
                        .accept(MediaType.TEXT_HTML),
                String
        )

        String fragmentHtml = fragmentResponse.body()
        String fullPageHtml = fullPageResponse.body()

        then: "the HTMX request returns only the fragment while the normal request returns the full HTML page"
        verifyAll {
            fragmentResponse.status == HttpStatus.OK
            fragmentHtml != null
            !fragmentHtml.contains("<!DOCTYPE html>")
            fragmentHtml.contains('id="discovery-results-shell"')
            fragmentHtml.contains('id="scheduled-shifts-queue"')

            fullPageResponse.status == HttpStatus.OK
            fullPageHtml != null
            fullPageHtml.contains("<!DOCTYPE html>")
            fullPageHtml.contains('id="where-input"')
            fullPageHtml.contains('id="discovery-results-shell"')
        }
    }

    def "homepage never renders a DRAFT or REJECTED project even when filter params are passed"() {
        given: "DRAFT, REJECTED, and ACTIVE projects whose titles sort before seeded projects"
        String draftTitle = "0000 Aaa Draft Secret Project"
        String rejectedTitle = "0000 Aab Rejected Secret Project"
        String activeTitle = "0000 Aac Active Featured Project"

        dataBuilder.project()
                .forOrg(TestPersonas.NATIONAL_RELIEF_NETWORK_ID)
                .withTitle(draftTitle)
                .withDescription("This draft project must never appear on the public homepage.")
                .withStatus(ProjectStatus.DRAFT)
                .persist()

        dataBuilder.project()
                .forOrg(TestPersonas.NATIONAL_RELIEF_NETWORK_ID)
                .withTitle(rejectedTitle)
                .withDescription("This rejected project must never appear on the public homepage.")
                .withStatus(ProjectStatus.REJECTED)
                .persist()

        dataBuilder.project()
                .forOrg(TestPersonas.NATIONAL_RELIEF_NETWORK_ID)
                .withTitle(activeTitle)
                .withDescription("This active project is safe to show on the public homepage.")
                .withStatus(ProjectStatus.ACTIVE)
                .persist()

        when: "visiting GET / with and without status or title query filters"
        String plainHome = client.exchange(
                HttpRequest.GET("/").accept(MediaType.TEXT_HTML),
                String
        ).body()
        String filteredDraftHome = client.exchange(
                HttpRequest.GET("/?status=DRAFT&q=Draft").accept(MediaType.TEXT_HTML),
                String
        ).body()
        String filteredRejectedHome = client.exchange(
                HttpRequest.GET("/?status=REJECTED&title=Rejected").accept(MediaType.TEXT_HTML),
                String
        ).body()

        then: "only the active project appears and DRAFT or REJECTED projects are never rendered"
        verifyAll {
            plainHome.contains(activeTitle)
            !plainHome.contains(draftTitle)
            !plainHome.contains(rejectedTitle)

            filteredDraftHome.contains(activeTitle)
            !filteredDraftHome.contains(draftTitle)
            !filteredDraftHome.contains(rejectedTitle)

            filteredRejectedHome.contains(activeTitle)
            !filteredRejectedHome.contains(draftTitle)
            !filteredRejectedHome.contains(rejectedTitle)
        }
    }

    @Unroll
    def "locations endpoint rejects out-of-bounds #coordLabel with 400 BAD_REQUEST"(String coordLabel, double longitude, double latitude) {
        when: "creating a location with invalid coordinates"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/locations", [
                name       : "Invalid Coord Site",
                addressLine: "100 Main St",
                city       : "Denver",
                countryCode: "US",
                longitude  : longitude,
                latitude   : latitude
        ])), Location)

        then: "it fails with 400 BAD_REQUEST"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        coordLabel      | longitude | latitude
        "longitude 181" | 181.0     | 39.74
        "latitude -91"  | -104.99   | -91.0
    }

    def "region director can create a location inside their region"() {
        given: "a valid create request inside Denver County"
        def createRequest = new CreateLocationRequest(
                "Denver Director Center",
                "1437 Bannock St",
                "Denver",
                "CO",
                "80202",
                "US",
                -104.9903,
                39.7392
        )

        when: "a region director creates the location inside their assigned region"
        HttpResponse<Location> createResponse = client.exchange(
                asRegionDirector(HttpRequest.POST("/locations", createRequest)),
                Location
        )
        Location created = track(createResponse.body())

        then: "the location is created successfully"
        verifyAll {
            createResponse.status == HttpStatus.OK
            created != null
            created.id() != null
            created.name() == "Denver Director Center"
            created.city() == "Denver"
        }
    }

    def "region director can update a location inside their region"() {
        given: "an existing location inside Denver County and a valid update request"
        Location existing = dataBuilder.location()
                .withName("Denver Director Center")
                .withAddress("1437 Bannock St", "Denver", "CO", "80202", "US")
                .atPoint(-104.9903, 39.7392)
                .persist()
        def updateRequest = new UpdateLocationRequest(
                "Denver Director Center Updated",
                "1437 Bannock St",
                "Denver",
                "CO",
                "80202",
                "US",
                -104.9900,
                39.7400
        )

        when: "the region director updates that location inside their assigned region"
        HttpResponse<Location> updateResponse = client.exchange(
                asRegionDirector(HttpRequest.PUT("/locations/${existing.id()}", updateRequest)),
                Location
        )
        Location updated = updateResponse.body()

        then: "the location is updated in the database"
        Location saved = locationRepository.findById(existing.id()).orElse(null)
        verifyAll {
            updateResponse.status == HttpStatus.OK
            updated.id() == existing.id()
            updated.name() == "Denver Director Center Updated"
            saved != null
            saved.name() == "Denver Director Center Updated"
        }
    }

    def "standard user is blocked from creating a location with 403 FORBIDDEN"() {
        given: "a valid create request inside Denver County"
        def createRequest = new CreateLocationRequest(
                "Denver Director Center",
                "1437 Bannock St",
                "Denver",
                "CO",
                "80202",
                "US",
                -104.9903,
                39.7392
        )

        when: "a standard user attempts to create a location"
        client.exchange(
                asStandardUser(HttpRequest.POST("/locations", createRequest)),
                Location
        )

        then: "the request is blocked with 403 FORBIDDEN"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "standard user is blocked from updating a location with 403 FORBIDDEN"() {
        given: "an existing location and an update request"
        Location existing = dataBuilder.location()
                .withName("Denver Director Center")
                .withAddress("1437 Bannock St", "Denver", "CO", "80202", "US")
                .atPoint(-104.9903, 39.7392)
                .persist()
        def updateRequest = new UpdateLocationRequest(
                "Denver Director Center Updated",
                "1437 Bannock St",
                "Denver",
                "CO",
                "80202",
                "US",
                -104.9900,
                39.7400
        )

        when: "a standard user attempts to update the location"
        client.exchange(
                asStandardUser(HttpRequest.PUT("/locations/${existing.id()}", updateRequest)),
                Location
        )

        then: "the request is blocked with 403 FORBIDDEN"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }
}
