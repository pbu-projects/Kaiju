package lol.pbu.kaiju.security

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.controller.BaseControllerSpec
import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.domain.Project
import lol.pbu.kaiju.fixtures.TestPersonas
import lol.pbu.kaiju.model.ProjectStatus
import lol.pbu.kaiju.model.ProjectType
import lol.pbu.kaiju.model.RegionUserRole
import lol.pbu.kaiju.model.VerificationStatus
import lol.pbu.kaiju.repository.LocationRepository
import lol.pbu.kaiju.repository.OrganizationRepository
import lol.pbu.kaiju.repository.ProjectRepository
import lol.pbu.kaiju.repository.SecurityQueryRepository
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.PrecisionModel
import spock.lang.Shared

import java.time.OffsetDateTime
import java.time.ZoneOffset

@MicronautTest(transactional = false)
class WhitehatSecurityAuditSpec extends BaseControllerSpec {

    @Inject
    ProjectRepository projectRepository

    @Inject
    OrganizationRepository organizationRepository

    @Inject
    LocationRepository locationRepository

    @Inject
    SecurityQueryRepository securityQueryRepository

    @Shared
    GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326)

    def "region director cannot add themselves to another region"() {
        given: "a region director assigned only to Denver County"
        def request = HttpRequest.POST("/region-users", [
                userId  : TestPersonas.DANIEL_COUNTY_DIRECTOR.id.toString(),
                regionId: TestPersonas.EL_PASO_COUNTY_REGION_ID.toString(),
                role    : RegionUserRole.REGION_DIRECTOR.name()
        ]).accept(MediaType.APPLICATION_JSON_TYPE)
        asRegionDirector(request, TestPersonas.DANIEL_COUNTY_DIRECTOR)

        when: "they try to assign themselves as director of El Paso County"
        client.exchange(request, Map)

        then: "access should be denied"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "region director cannot redraw their own border to cover the whole world"() {
        given: "Denver County director sends a globe-sized polygon for Denver County"
        def request = HttpRequest.PUT("/administrative-regions/${TestPersonas.DENVER_COUNTY_REGION_ID}", [
                name       : "Denver County Expanded",
                parentId   : TestPersonas.COLORADO_STATE_REGION_ID.toString(),
                coordinates: [
                        [longitude: -179.0, latitude: -89.0],
                        [longitude: 179.0, latitude: -89.0],
                        [longitude: 179.0, latitude: 89.0],
                        [longitude: -179.0, latitude: 89.0]
                ]
        ]).accept(MediaType.APPLICATION_JSON_TYPE)
        asRegionDirector(request, TestPersonas.DANIEL_COUNTY_DIRECTOR)

        when: "they try to update their own region polygon"
        client.exchange(request, Map)

        then: "access should be denied"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "region director cannot edit a location outside their region"() {
        given: "a location in Boulder outside Denver County"
        def boulderPoint = geometryFactory.createPoint(new Coordinate(-105.2705, 40.0150))
        def location = locationRepository.save(new Location(
                null, "Boulder Hall", "123 Pearl St", "Boulder", "CO", "80302", "US", boulderPoint
        ))
        trackLocation(location.id())

        def request = HttpRequest.PUT("/locations/${location.id()}", [
                name         : "Tampered Location",
                addressLine  : "123 Pearl St",
                city         : "Boulder",
                stateProvince: "CO",
                postalCode   : "80302",
                countryCode  : "US",
                longitude    : -104.9903,
                latitude     : 39.7392
        ]).accept(MediaType.APPLICATION_JSON_TYPE)
        asRegionDirector(request, TestPersonas.DANIEL_COUNTY_DIRECTOR)

        when: "Denver County director tries to move that Boulder location"
        client.exchange(request, Map)

        then: "access should be denied"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "anonymous visitor cannot read draft or soft-deleted projects by ID"() {
        given: "a draft project and a soft-deleted active project"
        def org = organizationRepository.findById(TestPersonas.NATIONAL_RELIEF_NETWORK_ID).orElseThrow()
        def now = OffsetDateTime.now(ZoneOffset.UTC)
        def draft = projectRepository.save(new Project(
                null, org, null, "Secret Draft Project",
                "This draft project should never be readable by anonymous visitors.",
                ProjectType.STANDARD, ProjectStatus.DRAFT,
                now, null, null, null, null
        ))
        trackProject(draft.id())

        def deleted = projectRepository.save(new Project(
                null, org, null, "Deleted Project",
                "This soft-deleted project should never be readable by anonymous visitors.",
                ProjectType.STANDARD, ProjectStatus.ACTIVE,
                now, now, null, null, null
        ))
        trackProject(deleted.id())

        when: "an anonymous visitor requests the draft project by ID"
        client.exchange(HttpRequest.GET("/projects/${draft.id()}").accept(MediaType.APPLICATION_JSON_TYPE), Map)

        then: "access should be denied or hidden"
        def e1 = thrown(HttpClientResponseException)
        e1.status in [HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND]

        when: "an anonymous visitor requests the soft-deleted project by ID"
        client.exchange(HttpRequest.GET("/projects/${deleted.id()}").accept(MediaType.APPLICATION_JSON_TYPE), Map)

        then: "access should be denied or hidden"
        def e2 = thrown(HttpClientResponseException)
        e2.status in [HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND]
    }

    def "standard user cannot read organization, project, or region audit logs"() {
        given: "a standard volunteer account"
        def orgLogReq = asStandardUser(HttpRequest.GET("/organization-audit-logs").accept(MediaType.APPLICATION_JSON_TYPE))
        def projLogReq = asStandardUser(HttpRequest.GET("/project-audit-logs").accept(MediaType.APPLICATION_JSON_TYPE))
        def regLogReq = asStandardUser(HttpRequest.GET("/administrative-region-audit-logs").accept(MediaType.APPLICATION_JSON_TYPE))

        when: "they request organization audit logs"
        client.exchange(orgLogReq, Map)

        then: "access should be denied"
        def e1 = thrown(HttpClientResponseException)
        e1.status == HttpStatus.FORBIDDEN

        when: "they request project audit logs"
        client.exchange(projLogReq, Map)

        then: "access should be denied"
        def e2 = thrown(HttpClientResponseException)
        e2.status == HttpStatus.FORBIDDEN

        when: "they request region audit logs"
        client.exchange(regLogReq, Map)

        then: "access should be denied"
        def e3 = thrown(HttpClientResponseException)
        e3.status == HttpStatus.FORBIDDEN
    }

    def "standard user cannot create a project under an organization they do not belong to"() {
        given: "a standard volunteer who is not a member of National Relief Network"
        def request = HttpRequest.POST("/projects", [
                organizationId: TestPersonas.NATIONAL_RELIEF_NETWORK_ID.toString(),
                title         : "Unauthorized Spoofed Project",
                description   : "A volunteer trying to post a project under an unrelated organization.",
                projectType   : ProjectType.STANDARD.name(),
                status        : ProjectStatus.DRAFT.name(),
                boundaries    : [[
                        name       : "Area",
                        coordinates: [
                                [longitude: -105.0, latitude: 39.7],
                                [longitude: -104.9, latitude: 39.7],
                                [longitude: -104.9, latitude: 39.8]
                        ]
                ]]
        ]).accept(MediaType.APPLICATION_JSON_TYPE)
        asStandardUser(request, TestPersonas.VICTOR_VOLUNTEER)

        when: "they submit a project under that organization"
        client.exchange(request, Map)

        then: "access should be denied"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "standard user cannot list org memberships, view private orgs, or create new users"() {
        given: "a private unverified organization"
        def privateOrg = organizationRepository.save(new Organization(
                null, "Private Secret Org", null, null,
                false, VerificationStatus.UNVERIFIED, null, null
        ))
        trackOrganization(privateOrg.id())

        when: "a standard user lists all organization memberships"
        client.exchange(asStandardUser(HttpRequest.GET("/organization-users").accept(MediaType.APPLICATION_JSON_TYPE)), Map)

        then: "access should be denied"
        def e1 = thrown(HttpClientResponseException)
        e1.status == HttpStatus.FORBIDDEN

        when: "a standard user looks up the private organization by ID"
        client.exchange(asStandardUser(HttpRequest.GET("/organizations/${privateOrg.id()}").accept(MediaType.APPLICATION_JSON_TYPE)), Map)

        then: "access should be denied or hidden"
        def e2 = thrown(HttpClientResponseException)
        e2.status in [HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND]

        when: "a standard user tries to create a brand new user account"
        def createUserReq = asStandardUser(HttpRequest.POST("/users", [
                email: "spam-account-${UUID.randomUUID()}@example.com".toString()
        ]).accept(MediaType.APPLICATION_JSON_TYPE))
        client.exchange(createUserReq, Map)

        then: "access should be denied"
        def e3 = thrown(HttpClientResponseException)
        e3.status == HttpStatus.FORBIDDEN
    }

    def "expired organization verification must not be treated as verified"() {
        given: "an organization whose verification expired yesterday"
        def expiredOrg = organizationRepository.save(new Organization(
                null, "Expired Org", null, null,
                true, VerificationStatus.VERIFIED,
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
                null
        ))
        trackOrganization(expiredOrg.id())

        expect: "expired org is not treated as verified by security or search endpoints"
        def searchRes = client.exchange(
                asStandardUser(HttpRequest.GET("/organizations/search-by-name?name=Expired%20Org").accept(MediaType.APPLICATION_JSON_TYPE)),
                Map
        )
        verifyAll {
            !securityQueryRepository.isOrgVerified(expiredOrg.id())
            !(searchRes.body().content as List).any { it.id == expiredOrg.id().toString() }
        }
    }
}
