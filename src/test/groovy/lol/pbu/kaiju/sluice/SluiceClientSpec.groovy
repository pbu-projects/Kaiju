package lol.pbu.kaiju.sluice

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.core.annotation.Nullable
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.QueryValue
import io.micronaut.http.client.exceptions.HttpClientException
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

@MicronautTest
@Property(name = "kaiju.sluice.url", value = "/")
@Property(name = "spec.name", value = "SluiceClientSpec")
class SluiceClientSpec extends Specification {

    @Inject
    SluiceClient sluiceClient

    @Inject
    SluiceService sluiceService

    def "retrieves all states through the client"() {
        when: "calling states endpoint"
        List<SluiceBoundaryDto> states = sluiceClient.getStates()

        then: "states list is returned and deserialized properly"
        verifyAll(states) {
            size() == 1
            first().id() == "state-ut"
            first().name() == "Utah"
            first().level() == SluiceBoundaryLevel.STATE
            first().stateCode() == "UT"
            first().geometryGeoJson() == "{\"type\":\"Polygon\"}"
            first().properties()?.get("fips") == "49"
        }
    }

    def "retrieves a single state by state code"() {
        when: "requesting existing state"
        Optional<SluiceBoundaryDto> result = sluiceClient.getState("UT")

        then: "state boundary is present with correct details"
        result.isPresent()
        verifyAll(result.get()) {
            id() == "state-ut"
            name() == "Utah"
            level() == SluiceBoundaryLevel.STATE
            stateCode() == "UT"
        }

        when: "requesting non-existent state"
        Optional<SluiceBoundaryDto> missing = sluiceClient.getState("ZZ")

        then: "empty optional is returned"
        missing.isEmpty()
    }

    def "queries counties with state filter"() {
        when: "fetching counties for Utah"
        List<SluiceBoundaryDto> counties = sluiceClient.getCounties("UT")

        then: "counties are returned with matching state code"
        verifyAll(counties) {
            size() == 1
            first().id() == "county-salt-lake"
            first().name() == "Salt Lake County"
            first().level() == SluiceBoundaryLevel.COUNTY
            first().stateCode() == "UT"
            first().countyName() == "Salt Lake"
        }
        MockSluiceController.lastQueryParams.get("state") == "UT"
    }

    def "queries cities with state and county filters"() {
        when: "fetching cities for Utah and Salt Lake"
        List<SluiceBoundaryDto> cities = sluiceClient.getCities("UT", "Salt Lake")

        then: "cities are returned with correct properties"
        verifyAll(cities) {
            size() == 1
            first().id() == "city-slc"
            first().name() == "Salt Lake City"
            first().level() == SluiceBoundaryLevel.CITY
            first().stateCode() == "UT"
            first().countyName() == "Salt Lake"
        }
        verifyAll(MockSluiceController.lastQueryParams) {
            get("state") == "UT"
            get("county") == "Salt Lake"
        }
    }

    def "resolves coordinates to administrative regions"() {
        when: "resolving coordinates for Salt Lake City"
        Optional<SluiceReverseGeocodeResult> result = sluiceClient.resolve(40.7608d, -111.8910d)

        then: "reverse geocode result contains civic boundaries and time zone"
        verifyAll {
            result.isPresent()
            result.get().state() == "UT"
            result.get().county() == "Salt Lake County"
            result.get().city() == "Salt Lake City"
            result.get().timeZone() == "America/Denver"
        }
    }

    def "checks health status through client"() {
        when: "calling health endpoint"
        Map<String, Object> health = sluiceClient.health()

        then: "health report is returned"
        verifyAll(health) {
            get("status") == "UP"
            get("service") == "sluice"
        }
    }

    def "service checks availability and finds boundaries"() {
        expect: "service reports available against healthy endpoint"
        verifyAll(sluiceService) {
            isAvailable()
            isEnabled()
        }

        when: "finding state boundary via service"
        Optional<SluiceBoundaryDto> stateBoundary = sluiceService.findStateBoundary("UT")

        then: "state boundary is returned"
        verifyAll(stateBoundary) {
            isPresent()
            get().name() == "Utah"
        }

        when: "resolving coordinates via service"
        Optional<SluiceReverseGeocodeResult> geoResult = sluiceService.resolveCoordinates(40.7608d, -111.8910d)

        then: "civic location is returned"
        verifyAll {
            geoResult.isPresent()
            geoResult.get().state() == "UT"
            geoResult.get().city() == "Salt Lake City"
        }

        when: "resolving coordinates outside known boundaries"
        Optional<SluiceReverseGeocodeResult> emptyResult = sluiceService.resolveCoordinates(89.0d, -140.0d)

        then: "empty optional is returned when all fields are null"
        emptyResult.isEmpty()

        when: "finding missing state boundary"
        Optional<SluiceBoundaryDto> missing = sluiceService.findStateBoundary("ZZ")

        then: "empty optional is returned"
        missing.isEmpty()

        when: "finding state with blank input"
        Optional<SluiceBoundaryDto> blank = sluiceService.findStateBoundary("   ")

        then: "empty optional is returned without remote call"
        blank.isEmpty()
    }

    def "service handles connection errors gracefully when sluice is unreachable"() {
        given: "a client that throws connection errors"
        SluiceClient brokenClient = Stub(SluiceClient) {
            health() >> { throw new HttpClientException("Connection refused") }
            getState(_) >> { throw new HttpClientException("Connection refused") }
            resolve(_, _) >> { throw new HttpClientException("Connection refused") }
            getStates() >> { throw new HttpClientException("Connection refused") }
            getCounties(_) >> { throw new HttpClientException("Connection refused") }
            getCities(_, _) >> { throw new HttpClientException("Connection refused") }
        }
        SluiceService fallbackService = new SluiceService(brokenClient, true)

        when: "calling methods against unreachable service"
        boolean available = fallbackService.isAvailable()
        def state = fallbackService.findStateBoundary("UT")
        def geo = fallbackService.resolveCoordinates(40.7608d, -111.8910d)
        def states = fallbackService.getStates()
        def counties = fallbackService.getCounties("UT")
        def cities = fallbackService.getCities("UT", "Salt Lake")

        then: "all service methods catch errors and return empty results safely"
        verifyAll {
            !available
            state.isEmpty()
            geo.isEmpty()
            states.isEmpty()
            counties.isEmpty()
            cities.isEmpty()
        }
    }

    def "service returns empty results when disabled in configuration"() {
        given: "a mock client that should never be invoked"
        SluiceClient unusedClient = Mock(SluiceClient)
        SluiceService disabledService = new SluiceService(unusedClient, false)

        when: "calling methods on disabled service"
        boolean available = disabledService.isAvailable()
        boolean enabled = disabledService.isEnabled()
        def state = disabledService.findStateBoundary("UT")
        def geo = disabledService.resolveCoordinates(40.7608d, -111.8910d)
        def states = disabledService.getStates()
        def counties = disabledService.getCounties("UT")
        def cities = disabledService.getCities("UT", "Salt Lake")

        then: "service reports disabled and returns empty results immediately"
        verifyAll {
            !enabled
            !available
            state.isEmpty()
            geo.isEmpty()
            states.isEmpty()
            counties.isEmpty()
            cities.isEmpty()
        }
        0 * unusedClient._
    }

    @Requires(property = "spec.name", value = "SluiceClientSpec")
    @Secured(SecurityRule.IS_ANONYMOUS)
    @Controller("/api/v1/boundaries")
    static class MockSluiceController {

        static final Map<String, String> lastQueryParams = [:]

        @Get("/states")
        List<SluiceBoundaryDto> getStates() {
            return [
                    new SluiceBoundaryDto("state-ut", "Utah", SluiceBoundaryLevel.STATE, "UT", null, "{\"type\":\"Polygon\"}", [fips: "49"])
            ]
        }

        @Get("/states/{stateCode}")
        HttpResponse<SluiceBoundaryDto> getState(@PathVariable String stateCode) {
            if ("UT".equalsIgnoreCase(stateCode)) {
                return HttpResponse.ok(new SluiceBoundaryDto("state-ut", "Utah", SluiceBoundaryLevel.STATE, "UT", null, "{\"type\":\"Polygon\"}", [fips: "49"]))
            }
            return HttpResponse.notFound()
        }

        @Get("/counties{?state}")
        List<SluiceBoundaryDto> getCounties(@Nullable @QueryValue String state) {
            lastQueryParams.put("state", state)
            return [
                    new SluiceBoundaryDto("county-salt-lake", "Salt Lake County", SluiceBoundaryLevel.COUNTY, state, "Salt Lake", "{\"type\":\"Polygon\"}", [fips: "49035"])
            ]
        }

        @Get("/cities{?state,county}")
        List<SluiceBoundaryDto> getCities(@Nullable @QueryValue String state, @Nullable @QueryValue String county) {
            lastQueryParams.put("state", state)
            lastQueryParams.put("county", county)
            return [
                    new SluiceBoundaryDto("city-slc", "Salt Lake City", SluiceBoundaryLevel.CITY, state, county, "{\"type\":\"Polygon\"}", [population: 200000])
            ]
        }

        @Get("/resolve{?lat,lon}")
        HttpResponse<SluiceReverseGeocodeResult> resolve(@QueryValue Double lat, @QueryValue Double lon) {
            lastQueryParams.put("lat", String.valueOf(lat))
            lastQueryParams.put("lon", String.valueOf(lon))
            if (lat != null && lat > 85.0d) {
                return HttpResponse.ok(new SluiceReverseGeocodeResult(null, null, null, null))
            }
            return HttpResponse.ok(new SluiceReverseGeocodeResult("UT", "Salt Lake County", "Salt Lake City", "America/Denver"))
        }

        @Get("/health")
        Map<String, Object> health() {
            return [status: "UP", service: "sluice"]
        }
    }
}
