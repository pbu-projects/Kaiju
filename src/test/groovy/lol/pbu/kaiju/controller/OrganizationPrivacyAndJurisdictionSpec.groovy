package lol.pbu.kaiju.controller

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
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

import java.sql.Timestamp
import java.time.OffsetDateTime
import java.util.UUID

@MicronautTest(transactional = false)
class OrganizationPrivacyAndJurisdictionSpec extends BaseControllerSpec {

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

    def cleanup() {
        if (projectAId != null) {
            executeUpdate("DELETE FROM shifts WHERE project_id = ?", projectAId)
            executeUpdate("DELETE FROM project_locations WHERE project_id = ?", projectAId)
            executeUpdate("DELETE FROM projects WHERE id = ?", projectAId)
        }
        if (projectBId != null) {
            executeUpdate("DELETE FROM shifts WHERE project_id = ?", projectBId)
            executeUpdate("DELETE FROM project_locations WHERE project_id = ?", projectBId)
            executeUpdate("DELETE FROM projects WHERE id = ?", projectBId)
        }
        if (shiftAId != null) {
            executeUpdate("DELETE FROM shifts WHERE id = ?", shiftAId)
        }
        if (shiftBId != null) {
            executeUpdate("DELETE FROM shifts WHERE id = ?", shiftBId)
        }
        if (locationAId != null) {
            executeUpdate("DELETE FROM organization_locations WHERE location_id = ?", locationAId)
            executeUpdate("DELETE FROM locations WHERE id = ?", locationAId)
        }
        if (locationBId != null) {
            executeUpdate("DELETE FROM organization_locations WHERE location_id = ?", locationBId)
            executeUpdate("DELETE FROM locations WHERE id = ?", locationBId)
        }
        if (orgBId != null && orgBId != TestPersonas.DENVER_METRO_BRANCH_ID) {
            executeUpdate("DELETE FROM organization_users WHERE organization_id = ?", orgBId)
            executeUpdate("DELETE FROM organization_regions WHERE organization_id = ?", orgBId)
            executeUpdate("DELETE FROM organizations WHERE id = ?", orgBId)
        }
    }

    private void provisionTenantFixtures() {
        denverRegionId = TestPersonas.DENVER_COUNTY_REGION_ID
        denverDirector = TestPersonas.DANIEL_COUNTY_DIRECTOR

        orgAId = TestPersonas.DENVER_METRO_BRANCH_ID
        userA = TestPersonas.LAURA_LOCAL_MANAGER
        locationAId = UUID.randomUUID()
        projectAId = UUID.randomUUID()
        shiftAId = UUID.randomUUID()

        orgBId = UUID.randomUUID()
        userB = TestPersonas.URSULA_UTAH_ADMIN
        locationBId = UUID.randomUUID()
        projectBId = UUID.randomUUID()
        shiftBId = UUID.randomUUID()

        // 1. Link Denver Metro Branch to Denver County Region if not already present
        executeUpdate("""
            INSERT INTO organization_regions (organization_id, region_id)
            VALUES (?, ?)
            ON CONFLICT DO NOTHING
        """, orgAId, denverRegionId)

        // 2. Organization Alpha (Org A) in Denver
        executeUpdate("""
            INSERT INTO locations (id, name, address_line, city, state_province, postal_code, country_code, geom)
            VALUES (?, 'Denver Location Alpha', '1600 California St', 'Denver', 'CO', '80202', ?, ST_GeogFromText(?))
        """, locationAId, COUNTRY_CODE_US, DENVER_POINT)

        executeUpdate("""
            INSERT INTO organization_locations (organization_id, location_id)
            VALUES (?, ?)
        """, orgAId, locationAId)

        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Project Alpha Denver', 'Description for Project Alpha Denver with at least twenty characters.', 'STANDARD', ?, NOW())
        """, projectAId, orgAId, STATUS_PENDING)

        executeUpdate("""
            INSERT INTO project_locations (project_id, location_id)
            VALUES (?, ?)
        """, projectAId, locationAId)

        def startTimeA = OffsetDateTime.now().plusDays(1)
        def endTimeA = startTimeA.plusHours(2)
        executeUpdate("""
            INSERT INTO shifts (id, project_id, is_virtual, location_id, start_time, end_time)
            VALUES (?, ?, true, NULL, ?, ?)
        """, shiftAId, projectAId, Timestamp.from(startTimeA.toInstant()), Timestamp.from(endTimeA.toInstant()))

        // 3. Organization Beta (Org B) in Boulder
        executeUpdate("""
            INSERT INTO organizations (id, name, is_public, verification_status)
            VALUES (?, 'Organization Beta', true, ?)
        """, orgBId, STATUS_VERIFIED)

        executeUpdate("""
            INSERT INTO locations (id, name, address_line, city, state_province, postal_code, country_code, geom)
            VALUES (?, 'Boulder Location Beta', '1000 Pearl St', 'Boulder', 'CO', '80302', ?, ST_GeogFromText(?))
        """, locationBId, COUNTRY_CODE_US, BOULDER_POINT)

        executeUpdate("""
            INSERT INTO organization_locations (organization_id, location_id)
            VALUES (?, ?)
        """, orgBId, locationBId)

        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Project Beta Boulder', 'Description for Project Beta Boulder with at least twenty characters.', 'STANDARD', ?, NOW())
        """, projectBId, orgBId, STATUS_PENDING)

        executeUpdate("""
            INSERT INTO project_locations (project_id, location_id)
            VALUES (?, ?)
        """, projectBId, locationBId)

        def startTimeB = OffsetDateTime.now().plusDays(2)
        def endTimeB = startTimeB.plusHours(2)
        executeUpdate("""
            INSERT INTO shifts (id, project_id, is_virtual, location_id, start_time, end_time)
            VALUES (?, ?, true, NULL, ?, ?)
        """, shiftBId, projectBId, Timestamp.from(startTimeB.toInstant()), Timestamp.from(endTimeB.toInstant()))
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
        response.status == HttpStatus.OK
        response.body().title() == "Updated Project Alpha Title"
    }

    def "HAPPY PATH | should allow User A to successfully create a shift for Project A with 200 OK"() {
        given: "a valid shift creation command for Project A"
        def command = createShiftCommand(projectAId)

        when: "User A creates a shift for Project A via HTTP POST"
        def response = client.exchange(asOrgManager(
                HttpRequest.POST(SHIFTS_PATH, command),
                userA
        ), Shift)

        then: "the response is 200 OK and shift is persisted"
        response.status == HttpStatus.OK
        response.body().id() != null
        response.body().project().id() == projectAId
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
        response.status == HttpStatus.OK
        response.body().id() == shiftAId
    }

    def "HAPPY PATH | should allow User A to successfully delete Shift A with 200 OK"() {
        when: "User A deletes Shift A via HTTP DELETE"
        def response = client.exchange(asOrgManager(
                HttpRequest.DELETE("${SHIFTS_PATH}/${shiftAId}"),
                userA
        ))

        then: "the response is 200 OK or 204 NO_CONTENT"
        response.status == HttpStatus.OK || response.status == HttpStatus.NO_CONTENT
        sql.firstRow("SELECT COUNT(*) as count FROM shifts WHERE id = ?", [shiftAId]).count == 0
    }

    def "HAPPY PATH | should allow Denver Regional Director to approve Denver Project A with 200 OK"() {
        when: "Denver Regional Director approves Denver Project A via HTTP PUT"
        def response = client.exchange(asRegionDirector(
                HttpRequest.PUT("${PROJECTS_PATH}/${projectAId}${STATUS_SUBPATH}", ""),
                denverDirector
        ), Project)

        then: "the response is 200 OK and project status transitions to ACTIVE"
        response.status == HttpStatus.OK
        response.body().status() == ProjectStatus.ACTIVE
    }
}
