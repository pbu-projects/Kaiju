package lol.pbu.kaiju.controller

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.Project
import lol.pbu.kaiju.domain.Shift
import lol.pbu.kaiju.dto.CoordinateDto
import lol.pbu.kaiju.dto.CreateShiftCommand
import lol.pbu.kaiju.dto.ProjectBoundaryCommand
import lol.pbu.kaiju.dto.UpdateLocationCommand
import lol.pbu.kaiju.dto.UpdateProjectCommand
import lol.pbu.kaiju.dto.UpdateShiftCommand
import lol.pbu.kaiju.fixtures.TestPersonas
import lol.pbu.kaiju.model.ProjectStatus
import lol.pbu.kaiju.model.ProjectType
import lol.pbu.kaiju.repository.ShiftRepository

import java.time.OffsetDateTime
import java.util.UUID

@MicronautTest(transactional = false)
class OrganizationPrivacyAndJurisdictionSpec extends BaseControllerSpec {

    @Inject
    ShiftRepository shiftRepository

    static final String STATUS_VERIFIED = "VERIFIED"
    static final String STATUS_PENDING = "PENDING"
    static final String COUNTRY_CODE_US = "US"

    static final String DENVER_POINT = "POINT(-104.9903 39.7392)"
    static final String BOULDER_POINT = "POINT(-105.2705 40.0150)"

    static final String PROJECTS_PATH = "/projects"
    static final String SHIFTS_PATH = "/shifts"
    static final String LOCATIONS_PATH = "/locations"
    static final String STATUS_SUBPATH = "/status"

    UUID orgAId
    TestPersonas.Persona userA
    UUID locationAId
    UUID projectAId
    UUID shiftAId

    UUID orgBId
    TestPersonas.Persona userB
    UUID locationBId
    UUID projectBId
    UUID shiftBId

    UUID denverRegionId
    TestPersonas.Persona denverDirector

    def setup() {
        provisionTenantFixtures()
    }

    private void provisionTenantFixtures() {
        denverRegionId = TestPersonas.DENVER_COUNTY_REGION_ID
        denverDirector = TestPersonas.DANIEL_COUNTY_DIRECTOR

        orgAId = TestPersonas.DENVER_METRO_BRANCH_ID
        userA = TestPersonas.LAURA_LOCAL_MANAGER

        userB = TestPersonas.URSULA_UTAH_ADMIN

        // 1. Link Denver Metro Branch to Denver County Region if not already present
        dataBuilder.linkOrganizationToRegion(orgAId, denverRegionId)

        // 2. Organization Alpha (Org A) in Denver
        def locA = dataBuilder.location()
                .withName("Denver Location Alpha")
                .withAddress("1600 California St", "Denver", "CO", "80202", COUNTRY_CODE_US)
                .atPoint(-104.9903, 39.7392)
                .persist()
        locationAId = locA.id()

        dataBuilder.linkOrganizationToLocation(orgAId, locationAId)

        def projectA = dataBuilder.project()
                .forOrg(orgAId)
                .withTitle("Project Alpha Denver")
                .withDescription("Description for Project Alpha Denver with at least twenty characters.")
                .withStatus(ProjectStatus.PENDING)
                .atLocation(locA)
                .persist()
        projectAId = projectA.id()

        def startTimeA = OffsetDateTime.now().plusDays(1)
        def endTimeA = startTimeA.plusHours(2)
        def shiftA = dataBuilder.shift()
                .forProject(projectA)
                .asVirtual()
                .withStartTime(startTimeA)
                .withEndTime(endTimeA)
                .persist()
        shiftAId = shiftA.id()

        // 3. Organization Beta (Org B) in Boulder
        def locB = dataBuilder.location()
                .withName("Boulder Location Beta")
                .withAddress("1000 Pearl St", "Boulder", "CO", "80302", COUNTRY_CODE_US)
                .atPoint(-105.2705, 40.0150)
                .persist()
        locationBId = locB.id()

        def orgB = dataBuilder.organization()
                .withName("Organization Beta")
                .asPublic()
                .verified()
                .withLocation(locB)
                .persist()
        orgBId = orgB.id()

        def projectB = dataBuilder.project()
                .forOrg(orgBId)
                .withTitle("Project Beta Boulder")
                .withDescription("Description for Project Beta Boulder with at least twenty characters.")
                .withStatus(ProjectStatus.PENDING)
                .atLocation(locB)
                .persist()
        projectBId = projectB.id()

        def startTimeB = OffsetDateTime.now().plusDays(2)
        def endTimeB = startTimeB.plusHours(2)
        def shiftB = dataBuilder.shift()
                .forProject(projectB)
                .asVirtual()
                .withStartTime(startTimeB)
                .withEndTime(endTimeB)
                .persist()
        shiftBId = shiftB.id()
    }

    private UpdateProjectCommand createUpdateProjectCommand(UUID orgId, String title, String description) {
        new UpdateProjectCommand(
                orgId,
                null,
                title,
                description,
                ProjectType.STANDARD,
                [],
                defaultBoundaries()
        )
    }

    private List<ProjectBoundaryCommand> defaultBoundaries() {
        [
                new ProjectBoundaryCommand(
                        "Denver Area Boundary",
                        [
                                new CoordinateDto(-105.1099, 39.7891),
                                new CoordinateDto(-104.7432, 39.7912),
                                new CoordinateDto(-104.7528, 39.6158),
                                new CoordinateDto(-105.0536, 39.6137),
                                new CoordinateDto(-105.1099, 39.7891)
                        ]
                )
        ]
    }

    private UpdateLocationCommand createUpdateLocationCommand() {
        new UpdateLocationCommand(
                "Attempted Updated Location",
                "123 Infiltrator St",
                "Boulder",
                "CO",
                "80302",
                COUNTRY_CODE_US,
                -105.2705,
                40.0150
        )
    }

    private CreateShiftCommand createShiftCommand(UUID projectId) {
        def startTime = OffsetDateTime.now().plusDays(3)
        def endTime = startTime.plusHours(2)
        new CreateShiftCommand(
                projectId,
                true,
                null,
                startTime,
                endTime,
                null
        )
    }

    private UpdateShiftCommand createUpdateShiftCommand() {
        def startTime = OffsetDateTime.now().plusDays(4)
        def endTime = startTime.plusHours(3)
        new UpdateShiftCommand(
                true,
                null,
                startTime,
                endTime,
                null
        )
    }

    /********** Horizontal Isolation (IDOR) Mutation Rejections **********/

    def "HORIZONTAL ISOLATION | should reject User A attempting to update Project B with 403 FORBIDDEN"() {
        given: "an update command targeting Project B"
        def command = createUpdateProjectCommand(
                orgBId,
                "Malicious Update Project B Title",
                "Malicious update attempt on Project B description exceeding twenty characters."
        )

        when: "User A attempts to update Project B via HTTP PUT"
        client.exchange(asOrgManager(
                HttpRequest.PUT("${PROJECTS_PATH}/${projectBId}", command),
                userA
        ), Project)

        then: "a 403 FORBIDDEN exception is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "HORIZONTAL ISOLATION | should reject User A attempting to delete Project B with 403 FORBIDDEN"() {
        when: "User A attempts to delete Project B via HTTP DELETE"
        client.exchange(asOrgManager(
                HttpRequest.DELETE("${PROJECTS_PATH}/${projectBId}"),
                userA
        ))

        then: "a 403 FORBIDDEN exception is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "HORIZONTAL ISOLATION | should reject User A attempting to create a shift linked to Project B with 403 FORBIDDEN"() {
        given: "a shift creation command linked to Project B"
        def command = createShiftCommand(projectBId)

        when: "User A attempts to create a shift for Project B via HTTP POST"
        client.exchange(asOrgManager(
                HttpRequest.POST(SHIFTS_PATH, command),
                userA
        ), Shift)

        then: "a 403 FORBIDDEN exception is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "HORIZONTAL ISOLATION | should reject User A attempting to update Shift B with 403 FORBIDDEN"() {
        given: "a shift update command"
        def command = createUpdateShiftCommand()

        when: "User A attempts to update Shift B via HTTP PUT"
        client.exchange(asOrgManager(
                HttpRequest.PUT("${SHIFTS_PATH}/${shiftBId}", command),
                userA
        ), Shift)

        then: "a 403 FORBIDDEN exception is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "HORIZONTAL ISOLATION | should reject User A attempting to delete Shift B with 403 FORBIDDEN"() {
        when: "User A attempts to delete Shift B via HTTP DELETE"
        client.exchange(asOrgManager(
                HttpRequest.DELETE("${SHIFTS_PATH}/${shiftBId}"),
                userA
        ))

        then: "a 403 FORBIDDEN exception is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "HORIZONTAL ISOLATION | should reject User A attempting to update Location B with 403 FORBIDDEN"() {
        given: "a location update command"
        def command = createUpdateLocationCommand()

        when: "User A attempts to update Location B via HTTP PUT"
        client.exchange(asOrgManager(
                HttpRequest.PUT("${LOCATIONS_PATH}/${locationBId}", command),
                userA
        ))

        then: "a 403 FORBIDDEN exception is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "HORIZONTAL ISOLATION | should reject User A attempting to delete Location B with 403 FORBIDDEN"() {
        when: "User A attempts to delete Location B via HTTP DELETE"
        client.exchange(asOrgManager(
                HttpRequest.DELETE("${LOCATIONS_PATH}/${locationBId}"),
                userA
        ))

        then: "a 403 FORBIDDEN exception is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    /********** Out-of-Region Spatial Approval Rejection **********/

    def "SPATIAL BOUNDARY ISOLATION | should reject Denver Regional Director attempting to approve Boulder Project B with 403 FORBIDDEN"() {
        when: "Denver Regional Director attempts to approve Boulder Project B via HTTP PUT"
        client.exchange(asRegionDirector(
                HttpRequest.PUT("${PROJECTS_PATH}/${projectBId}${STATUS_SUBPATH}", ""),
                denverDirector
        ), Project)

        then: "a 403 FORBIDDEN exception is returned because Project B is outside Denver boundary polygon"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    /********** Contrast / Happy Path Scenarios **********/

    def "HAPPY PATH | should allow User A to successfully update Project A with 200 OK"() {
        given: "a valid update command for Project A"
        def command = createUpdateProjectCommand(
                orgAId,
                "Updated Project Alpha Title",
                "Legitimate update on Project Alpha description exceeding twenty characters."
        )

        when: "User A updates Project A via HTTP PUT"
        def response = client.exchange(asOrgManager(
                HttpRequest.PUT("${PROJECTS_PATH}/${projectAId}", command),
                userA
        ), Project)

        then: "the response is 200 OK and project title is updated"
        verifyAll(response) {
            status == HttpStatus.OK
            body().title() == "Updated Project Alpha Title"
        }
    }

    def "HAPPY PATH | should allow User A to successfully create a shift for Project A with 200 OK"() {
        given: "a valid shift creation command for Project A"
        def command = createShiftCommand(projectAId)

        when: "User A creates a shift for Project A via HTTP POST"
        def response = client.exchange(asOrgManager(
                HttpRequest.POST(SHIFTS_PATH, command),
                userA
        ), Shift)
        track(response.body())

        then: "the response is 200 OK and shift is persisted"
        verifyAll(response) {
            status == HttpStatus.OK
            body().id() != null
            body().project().id() == projectAId
        }
    }

    def "HAPPY PATH | should allow User A to successfully update Shift A with 200 OK"() {
        given: "a valid shift update command for Shift A"
        def command = createUpdateShiftCommand()

        when: "User A updates Shift A via HTTP PUT"
        def response = client.exchange(asOrgManager(
                HttpRequest.PUT("${SHIFTS_PATH}/${shiftAId}", command),
                userA
        ), Shift)

        then: "the response is 200 OK"
        verifyAll(response) {
            status == HttpStatus.OK
            body().id() == shiftAId
        }
    }

    def "HAPPY PATH | should allow User A to successfully delete Shift A with 200 OK"() {
        when: "User A deletes Shift A via HTTP DELETE"
        def response = client.exchange(asOrgManager(
                HttpRequest.DELETE("${SHIFTS_PATH}/${shiftAId}"),
                userA
        ))

        then: "the response is 200 OK or 204 NO_CONTENT"
        verifyAll {
            response.status == HttpStatus.OK || response.status == HttpStatus.NO_CONTENT
            !shiftRepository.findById(shiftAId).isPresent()
            !shiftRepository.existsById(shiftAId)
        }
    }

    def "HAPPY PATH | should allow Denver Regional Director to approve Denver Project A with 200 OK"() {
        when: "Denver Regional Director approves Denver Project A via HTTP PUT"
        def response = client.exchange(asRegionDirector(
                HttpRequest.PUT("${PROJECTS_PATH}/${projectAId}${STATUS_SUBPATH}", ""),
                denverDirector
        ), Project)

        then: "the response is 200 OK and project status transitions to ACTIVE"
        verifyAll(response) {
            status == HttpStatus.OK
            body().status() == ProjectStatus.ACTIVE
        }
    }
}
