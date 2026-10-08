package lol.pbu.kaiju.controller

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.core.async.publisher.Publishers
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.BlockingHttpClient
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.filters.AuthenticationFetcher
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.inject.Singleton
import lol.pbu.kaiju.domain.AdministrativeRegion
import lol.pbu.kaiju.repository.AdministrativeRegionRepository
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.PrecisionModel
import org.reactivestreams.Publisher
import spock.lang.Shared
import spock.lang.Specification

@Property(name = "spec.name", value = "AdministrativeRegionSecurityIntegrationSpec")
@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.oauth2.enabled", value = "false")
@Property(name = "micronaut.security.token.jwt.enabled", value = "false")
@MicronautTest(transactional = false)
class AdministrativeRegionSecurityIntegrationSpec extends Specification {

    @Requires(property = "spec.name", value = "AdministrativeRegionSecurityIntegrationSpec")
    @Singleton
    static class TestAuthenticationFetcher implements AuthenticationFetcher<HttpRequest<?>> {
        @Override
        Publisher<Authentication> fetchAuthentication(HttpRequest<?> request) {
            String testUser = request.getHeaders().get("X-Test-User")
            if (testUser) {
                List<String> roles = request.getHeaders().getAll("X-Test-Role")
                return Publishers.just(Authentication.build(testUser, roles))
            }
            return Publishers.empty()
        }
    }

    @Inject
    @Client("/administrative-regions")
    HttpClient httpClient

    @Inject
    AdministrativeRegionRepository administrativeRegionRepository

    @Shared
    GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326)

    BlockingHttpClient getClient() {
        return httpClient.toBlocking()
    }

    private AdministrativeRegion createPersistedRegion(String name) {
        Coordinate[] coords = [
                new Coordinate(-105.0, 39.0),
                new Coordinate(-104.0, 39.0),
                new Coordinate(-104.0, 40.0),
                new Coordinate(-105.0, 40.0),
                new Coordinate(-105.0, 39.0)
        ]
        def geom = geometryFactory.createPolygon(coords)
        return administrativeRegionRepository.save(new AdministrativeRegion(null, name, null, geom))
    }

    /********** 401 UNAUTHORIZED Tests **********/

    def "Security | should reject unauthenticated GET /administrative-regions with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller requests the regions list"
        client.exchange(HttpRequest.GET("/").accept(MediaType.APPLICATION_JSON_TYPE))

        then: "an UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "Security | should reject unauthenticated GET /administrative-regions/{id} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller requests a specific region by ID"
        client.exchange(HttpRequest.GET("/${UUID.randomUUID()}").accept(MediaType.APPLICATION_JSON_TYPE))

        then: "an UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "Security | should reject unauthenticated POST /administrative-regions with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to create a region"
        client.exchange(HttpRequest.POST("/", [name: "Unauthenticated Region"]).accept(MediaType.APPLICATION_JSON_TYPE))

        then: "an UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "Security | should reject unauthenticated PUT /administrative-regions/{id} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to update a region"
        client.exchange(HttpRequest.PUT("/${UUID.randomUUID()}", [name: "Unauthenticated Update"]).accept(MediaType.APPLICATION_JSON_TYPE))

        then: "an UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "Security | should reject unauthenticated DELETE /administrative-regions/{id} with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to delete a region"
        client.exchange(HttpRequest.DELETE("/${UUID.randomUUID()}").accept(MediaType.APPLICATION_JSON_TYPE))

        then: "an UNAUTHORIZED response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** 403 FORBIDDEN Tests (Privilege Containment) **********/

    def "Security | should reject standard volunteer attempting POST /administrative-regions with 403 FORBIDDEN"() {
        when: "a standard authenticated volunteer attempts to create a region"
        client.exchange(
                HttpRequest.POST("/", [name: "Volunteer Rogue Region"])
                        .header("X-Test-User", "volunteer-user")
                        .header("X-Test-Role", "STANDARD_USER")
                        .accept(MediaType.APPLICATION_JSON_TYPE)
        )

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "Security | should reject regional agent attempting POST /administrative-regions with 403 FORBIDDEN"() {
        when: "a regional agent lacking system:admin attempts to create a region"
        client.exchange(
                HttpRequest.POST("/", [name: "Agent Region"])
                        .header("X-Test-User", "region-agent")
                        .header("X-Test-Role", "region:manage")
                        .accept(MediaType.APPLICATION_JSON_TYPE)
        )

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "Security | should reject standard volunteer attempting PUT /administrative-regions/{id} with 403 FORBIDDEN"() {
        given: "an existing region"
        def region = createPersistedRegion("Region To Mutate")

        when: "a standard volunteer attempts to update the region"
        client.exchange(
                HttpRequest.PUT("/${region.id()}", [name: "Unauthorized Update"])
                        .header("X-Test-User", "volunteer-user")
                        .header("X-Test-Role", "STANDARD_USER")
                        .accept(MediaType.APPLICATION_JSON_TYPE)
        )

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "Security | should reject standard volunteer attempting DELETE /administrative-regions/{id} with 403 FORBIDDEN"() {
        given: "an existing region"
        def region = createPersistedRegion("Region To Delete")

        when: "a standard volunteer attempts to delete the region"
        client.exchange(
                HttpRequest.DELETE("/${region.id()}")
                        .header("X-Test-User", "volunteer-user")
                        .header("X-Test-Role", "STANDARD_USER")
                        .accept(MediaType.APPLICATION_JSON_TYPE)
        )

        then: "a 403 FORBIDDEN response is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    /********** 200 OK Success Tests for Authorized Callers **********/

    def "Security | should allow standard authenticated volunteer to execute GET /administrative-regions"() {
        when: "an authenticated volunteer reads administrative regions"
        try {
            def response = client.exchange(
                    HttpRequest.GET("/?size=10")
                            .header("X-Test-User", "volunteer-user")
                            .header("X-Test-Role", "STANDARD_USER")
                            .accept(MediaType.APPLICATION_JSON_TYPE)
            )
            assert response.status == HttpStatus.OK
        } catch (HttpClientResponseException e) {
            System.err.println("GET / FAILED WITH: " + e.status + " BODY: " + (e.response.getBody(String).orElse(null)))
            throw e
        }

        then:
        noExceptionThrown()
    }

    def "Security | should allow standard authenticated volunteer to execute GET /administrative-regions/{id}"() {
        given: "an existing region"
        def region = createPersistedRegion("Readable Region")

        when: "an authenticated volunteer reads a specific region by ID"
        try {
            def response = client.exchange(
                    HttpRequest.GET("/${region.id()}")
                            .header("X-Test-User", "volunteer-user")
                            .header("X-Test-Role", "STANDARD_USER")
                            .accept(MediaType.APPLICATION_JSON_TYPE)
            )
            assert response.status == HttpStatus.OK
        } catch (HttpClientResponseException e) {
            System.err.println("GET /{id} FAILED WITH: " + e.status + " BODY: " + (e.response.getBody(String).orElse(null)))
            throw e
        }

        then:
        noExceptionThrown()
    }

    def "Security | should allow global admin with system:admin claim to delete region"() {
        given: "an existing region to delete"
        def region = createPersistedRegion("Admin Delete Target")

        when: "a platform admin deletes the region"
        def response = client.exchange(
                HttpRequest.DELETE("/${region.id()}")
                        .header("X-Test-User", "global-admin")
                        .header("X-Test-Role", "system:admin")
                        .accept(MediaType.APPLICATION_JSON_TYPE)
        )

        then: "the deletion succeeds with 200 OK"
        response.status == HttpStatus.OK

        and: "the region is removed from the database"
        !administrativeRegionRepository.findById(region.id()).isPresent()
    }
}
