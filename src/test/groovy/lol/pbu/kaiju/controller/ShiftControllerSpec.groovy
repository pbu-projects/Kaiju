package lol.pbu.kaiju.controller

import io.micronaut.context.annotation.Property
import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.domain.Project
import lol.pbu.kaiju.domain.Shift
import lol.pbu.kaiju.dto.CreateShiftCommand
import lol.pbu.kaiju.dto.UpdateShiftCommand
import lol.pbu.kaiju.repository.ShiftRepository
import spock.lang.Shared
import spock.lang.Unroll

import java.sql.Timestamp
import java.time.OffsetDateTime
import java.util.UUID

import static java.time.temporal.ChronoUnit.SECONDS
import static lol.pbu.kaiju.model.ProjectStatus.DRAFT
import static lol.pbu.kaiju.model.ProjectType.STANDARD
import static lol.pbu.kaiju.model.VerificationStatus.UNVERIFIED

@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.oauth2.enabled", value = "false")
@Property(name = "micronaut.security.token.jwt.enabled", value = "false")
@MicronautTest(transactional = false)
class ShiftControllerSpec extends BaseControllerSpec {

    @Inject
    ShiftRepository shiftRepository

    @Inject
    ShiftController shiftController

    @Shared
    UUID adminId = UUID.randomUUID()

    def setupSpec() {
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, 'shift-admin@example.com', 'GLOBAL_ADMIN') ON CONFLICT DO NOTHING", adminId)
    }

    def cleanupSpec() {
        executeUpdate("DELETE FROM users WHERE id = ?", adminId)
    }

    private Project getRandomProject() {
        def projectRow = sql.firstRow("SELECT id, title FROM projects LIMIT 1")
        if (!projectRow) {
            throw new IllegalStateException("No projects found in database to link shift to.")
        }
        def org = new Organization(UUID.randomUUID(), "Dummy Org", null, null, true, UNVERIFIED, null, [])
        new Project(projectRow.id as UUID, org, null, projectRow.title as String, "Desc", STANDARD, DRAFT, OffsetDateTime.now(), null, null, [], [])
    }

    private Location getRandomLocation() {
        def locationRow = sql.firstRow("SELECT id, name FROM locations LIMIT 1")
        if (!locationRow) {
            throw new IllegalStateException("No locations found in database to link shift to.")
        }
        new Location(locationRow.id as UUID, locationRow.name as String, "123 St", "City", null, null, "US", null)
    }

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid virtual shift when called by admin"() {
        given: "a new valid virtual shift command"
        def project = getRandomProject()
        def startTime = OffsetDateTime.now()
        def endTime = startTime.plusHours(2)
        def command = new CreateShiftCommand(project.id(), true, null, startTime, endTime, null)

        when: "the shift is added by admin via HTTP POST"
        def response = client.exchange(asGlobalAdmin(HttpRequest.POST("/shifts", command), adminId.toString()), Shift)
        Shift saved = response.body()

        then: "the response is 200 OK and shift is persisted with a generated ID"
        response.status == HttpStatus.OK
        verifyAll {
            saved.id() != null
            saved.project().id() == project.id()
            saved.isVirtual()
            saved.location() == null
        }

        and: "it can be retrieved from the database"
        def result = sql.firstRow("SELECT * FROM shifts WHERE id = ?", [saved.id()])
        verifyAll(result) {
            saved.id() == id
            saved.project().id() == project_id
            is_virtual == true
            location_id == null
        }

        cleanup:
        if (saved?.id() != null) {
            executeUpdate("DELETE FROM shifts WHERE id = ?", saved.id())
        }
    }

    def "CREATE | should successfully save a valid physical shift when called by admin"() {
        given: "a new valid physical shift command"
        def project = getRandomProject()
        def location = getRandomLocation()
        def startTime = OffsetDateTime.now()
        def endTime = startTime.plusHours(2)
        def command = new CreateShiftCommand(project.id(), false, location.id(), startTime, endTime, null)

        when: "the shift is added by admin via HTTP POST"
        def response = client.exchange(asGlobalAdmin(HttpRequest.POST("/shifts", command), adminId.toString()), Shift)
        Shift saved = response.body()

        then: "the response is 200 OK and shift is persisted with a generated ID"
        response.status == HttpStatus.OK
        verifyAll {
            saved.id() != null
            saved.project().id() == project.id()
            (!saved.isVirtual())
            saved.location().id() == location.id()
        }

        and: "it can be retrieved from the database"
        def result = sql.firstRow("SELECT * FROM shifts WHERE id = ?", [saved.id()])
        verifyAll(result) {
            saved.id() == id
            saved.project().id() == project_id
            is_virtual == false
            saved.location().id() == location_id
        }

        cleanup:
        if (saved?.id() != null) {
            executeUpdate("DELETE FROM shifts WHERE id = ?", saved.id())
        }
    }

    def "CREATE | should succeed when called by org manager of project organization"() {
        given: "a project with a verified org and an org manager"
        def project = getRandomProject()
        def realOrgId = sql.firstRow("SELECT organization_id FROM projects WHERE id = ?", [project.id()]).organization_id as UUID
        UUID managerUserId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", managerUserId, "shift-mgr-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MANAGER')", managerUserId, realOrgId)
        def command = new CreateShiftCommand(project.id(), true, null, OffsetDateTime.now(), OffsetDateTime.now().plusHours(2), null)

        when: "the org manager adds shift via HTTP POST"
        def response = client.exchange(authenticated(HttpRequest.POST("/shifts", command), managerUserId.toString(), ["STANDARD_USER"]), Shift)
        Shift saved = response.body()

        then: "the shift is persisted with 200 OK"
        response.status == HttpStatus.OK
        saved.id() != null

        cleanup:
        if (saved?.id() != null) {
            executeUpdate("DELETE FROM shifts WHERE id = ?", saved.id())
        }
        executeUpdate("DELETE FROM organization_users WHERE user_id = ? AND organization_id = ?", managerUserId, realOrgId)
        executeUpdate("DELETE FROM users WHERE id = ?", managerUserId)
    }

    def "CREATE | should throw 403 Forbidden when standard user attempts to add shift for unmanaged project"() {
        given: "a standard user and a project"
        UUID unauthUserId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", unauthUserId, "unauth-shift-${UUID.randomUUID()}@example.com".toString())
        def project = getRandomProject()
        def command = new CreateShiftCommand(project.id(), true, null, OffsetDateTime.now(), OffsetDateTime.now().plusHours(2), null)

        when: "the standard user attempts to add shift via HTTP POST"
        client.exchange(authenticated(HttpRequest.POST("/shifts", command), unauthUserId.toString(), ["STANDARD_USER"]), Shift)

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN

        cleanup:
        executeUpdate("DELETE FROM users WHERE id = ?", unauthUserId)
    }

    def "CREATE | should reject unauthenticated POST /shifts with 401 UNAUTHORIZED"() {
        given: "a valid create shift command"
        def project = getRandomProject()
        def command = new CreateShiftCommand(project.id(), true, null, OffsetDateTime.now(), OffsetDateTime.now().plusHours(2), null)

        when: "an unauthenticated caller attempts to add shift"
        client.exchange(HttpRequest.POST("/shifts", command), Shift)

        then: "a 401 UNAUTHORIZED is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    @Unroll
    def "CREATE | should throw 400 Bad Request on invalid location logic: #testCase"(String testCase, boolean isVirtual, UUID locationId) {
        given: "a command with invalid location logic"
        def project = getRandomProject()
        def command = new CreateShiftCommand(project.id(), isVirtual, locationId, OffsetDateTime.now(), OffsetDateTime.now().plusHours(2), null)

        when: "attempting to create shift via HTTP POST"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/shifts", command), adminId.toString()), Shift)

        then: "a 400 Bad Request is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        testCase                            | isVirtual | locationId
        "physical shift without locationId" | false     | null
        "virtual shift with locationId"     | true      | UUID.randomUUID()
    }

    def "CREATE | should throw 404 Not Found when project does not exist"() {
        given: "a non-existent project id"
        def nonExistentProjectId = UUID.randomUUID()
        def command = new CreateShiftCommand(nonExistentProjectId, true, null, OffsetDateTime.now(), OffsetDateTime.now().plusHours(2), null)

        when: "attempting to create shift"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/shifts", command), adminId.toString()), Shift)

        then: "a 404 Not Found is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    /********** READ Tests **********/

    def "READ | should retrieve an existing shift by ID"() {
        given: "an existing shift ID from the database"
        def firstRow = sql.firstRow("SELECT id FROM shifts LIMIT 1")
        assert firstRow != null
        UUID id = firstRow.id as UUID

        when: "the shift is requested by its ID via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/shifts/${id}"), adminId.toString()), Shift)

        then: "the correct shift is returned with 200 OK"
        response.status == HttpStatus.OK
        response.body().id() == id
    }

    def "READ | should return 404 Not Found for a non-existent shift ID"() {
        when: "a non-existent shift is requested via HTTP GET"
        client.exchange(asGlobalAdmin(HttpRequest.GET("/shifts/${UUID.randomUUID()}"), adminId.toString()), Shift)

        then: "a 404 NOT FOUND is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "READ | should reject unauthenticated GET /shifts/{id} with 401 UNAUTHORIZED"() {
        given: "an existing shift ID"
        def firstRow = sql.firstRow("SELECT id FROM shifts LIMIT 1")
        assert firstRow != null
        UUID id = firstRow.id as UUID

        when: "an unauthenticated caller requests a shift"
        client.exchange(HttpRequest.GET("/shifts/${id}"), Shift)

        then: "a 401 UNAUTHORIZED is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** UPDATE Tests **********/

    def "UPDATE | should successfully update an existing shift when called by admin"() {
        given: "an existing shift's details"
        def shiftRow = sql.firstRow("SELECT id FROM shifts LIMIT 1")
        assert shiftRow != null
        UUID id = shiftRow.id as UUID
        def newStart = OffsetDateTime.now().plusDays(1)
        def newEnd = newStart.plusHours(4)
        def updateCommand = new UpdateShiftCommand(true, null, newStart, newEnd, null)

        when: "the shift is updated by admin via HTTP PUT"
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/shifts/${id}", updateCommand), adminId.toString()), Shift)
        Shift updated = response.body()

        then: "the response is 200 OK and contains updated data"
        response.status == HttpStatus.OK
        verifyAll {
            updated.id() == id
            updated.startTime() == newStart
            updated.endTime() == newEnd
        }

        and: "the changes are persisted in the database"
        def dbResult = sql.firstRow("SELECT start_time, end_time FROM shifts WHERE id = ?", [id])
        dbResult != null
        ((Timestamp) dbResult.start_time).toInstant().truncatedTo(SECONDS) == newStart.toInstant().truncatedTo(SECONDS)
        ((Timestamp) dbResult.end_time).toInstant().truncatedTo(SECONDS) == newEnd.toInstant().truncatedTo(SECONDS)
    }

    def "UPDATE | should throw 403 Forbidden when standard user attempts to update unmanaged shift"() {
        given: "an existing shift and an unauthorized user"
        def shiftRow = sql.firstRow("SELECT id FROM shifts LIMIT 1")
        assert shiftRow != null
        UUID id = shiftRow.id as UUID
        UUID unauthUserId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", unauthUserId, "unauth-upd-sh-${UUID.randomUUID()}@example.com".toString())
        def updateCommand = new UpdateShiftCommand(true, null, OffsetDateTime.now().plusDays(1), OffsetDateTime.now().plusDays(1).plusHours(2), null)

        when: "unauthorized user attempts to update shift via HTTP PUT"
        client.exchange(authenticated(HttpRequest.PUT("/shifts/${id}", updateCommand), unauthUserId.toString(), ["STANDARD_USER"]), Shift)

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN

        cleanup:
        executeUpdate("DELETE FROM users WHERE id = ?", unauthUserId)
    }

    def "UPDATE | should reject unauthenticated PUT /shifts/{id} with 401 UNAUTHORIZED"() {
        given: "an existing shift ID and update command"
        def shiftRow = sql.firstRow("SELECT id FROM shifts LIMIT 1")
        assert shiftRow != null
        UUID id = shiftRow.id as UUID
        def updateCommand = new UpdateShiftCommand(true, null, OffsetDateTime.now().plusDays(1), OffsetDateTime.now().plusDays(1).plusHours(2), null)

        when: "an unauthenticated caller attempts to update a shift"
        client.exchange(HttpRequest.PUT("/shifts/${id}", updateCommand), Shift)

        then: "a 401 UNAUTHORIZED is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "UPDATE | should fail to update a non-existent shift when called by admin"() {
        given: "a random non-existent ID and an update command"
        def nonExistentId = UUID.randomUUID()
        def updateCommand = new UpdateShiftCommand(true, null, OffsetDateTime.now(), OffsetDateTime.now().plusHours(2), null)

        when: "an update is attempted by admin via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/shifts/${nonExistentId}", updateCommand), adminId.toString()), Shift)

        then: "a 404 NOT FOUND is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    @Unroll
    def "UPDATE | should throw 400 Bad Request on invalid location logic: #testCase"(String testCase, boolean isVirtual, UUID locationId) {
        given: "an existing shift and an update command with invalid location logic"
        def shiftRow = sql.firstRow("SELECT id FROM shifts LIMIT 1")
        assert shiftRow != null
        UUID id = shiftRow.id as UUID
        def updateCommand = new UpdateShiftCommand(isVirtual, locationId, OffsetDateTime.now().plusDays(1), OffsetDateTime.now().plusDays(1).plusHours(2), null)

        when: "attempting to update shift via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/shifts/${id}", updateCommand), adminId.toString()), Shift)

        then: "a 400 Bad Request is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        testCase                            | isVirtual | locationId
        "physical shift without locationId" | false     | null
        "virtual shift with locationId"     | true      | UUID.randomUUID()
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing shift when called by admin"() {
        given: "a new shift to be deleted"
        def project = getRandomProject()
        def command = new CreateShiftCommand(project.id(), true, null, OffsetDateTime.now(), OffsetDateTime.now().plusHours(2), null)
        def createResponse = client.exchange(asGlobalAdmin(HttpRequest.POST("/shifts", command), adminId.toString()), Shift)
        UUID id = createResponse.body().id()
        assert shiftRepository.existsById(id)

        when: "the shift is deleted by admin via HTTP DELETE"
        def response = client.exchange(asGlobalAdmin(HttpRequest.DELETE("/shifts/${id}"), adminId.toString()))

        then: "the response is 200 OK"
        response.status == HttpStatus.OK

        and: "the shift no longer exists in the repository or database"
        verifyAll {
            !shiftRepository.findById(id).isPresent()
            sql.firstRow("SELECT count(*) as count FROM shifts WHERE id = ?", [id]).count == 0
        }
    }

    def "DELETE | should throw 403 Forbidden when standard user attempts to delete unmanaged shift"() {
        given: "an existing shift and an unauthorized user"
        def shiftRow = sql.firstRow("SELECT id FROM shifts LIMIT 1")
        assert shiftRow != null
        UUID id = shiftRow.id as UUID
        UUID unauthUserId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", unauthUserId, "unauth-del-sh-${UUID.randomUUID()}@example.com".toString())

        when: "unauthorized user attempts to delete shift via HTTP DELETE"
        client.exchange(authenticated(HttpRequest.DELETE("/shifts/${id}"), unauthUserId.toString(), ["STANDARD_USER"]))

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN

        cleanup:
        executeUpdate("DELETE FROM users WHERE id = ?", unauthUserId)
    }

    def "DELETE | should reject unauthenticated DELETE /shifts/{id} with 401 UNAUTHORIZED"() {
        given: "an existing shift ID"
        def shiftRow = sql.firstRow("SELECT id FROM shifts LIMIT 1")
        assert shiftRow != null
        UUID id = shiftRow.id as UUID

        when: "an unauthenticated caller attempts to delete a shift"
        client.exchange(HttpRequest.DELETE("/shifts/${id}"))

        then: "a 401 UNAUTHORIZED is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    def "DELETE | should fail to delete a non-existent shift when called by admin"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a delete is attempted by admin via HTTP DELETE"
        client.exchange(asGlobalAdmin(HttpRequest.DELETE("/shifts/${nonExistentId}"), adminId.toString()))

        then: "a 404 NOT FOUND is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    /********** LIST Tests **********/

    def "LIST | should fully drain all shifts sequentially using cursors"() {
        setup:
        Set<Shift> allShifts = new LinkedHashSet<>()
        int pageSize = 5
        def pageable = CursoredPageable.from(pageSize, Sort.of(Sort.Order.asc("id")))

        when: "iterating through pages until no more data remains"
        while (pageable != null) {
            CursoredPage<Shift> page = shiftController.getShifts(pageable)
            allShifts.addAll(page.content)
            pageable = page.hasNext() ? page.nextPageable() : null
        }

        then: "the collected set contains all shifts from the database"
        def totalCount = sql.firstRow("SELECT count(*) as count FROM shifts").count
        verifyAll {
            allShifts.size() == totalCount
            allShifts.size() >= 2
        }
    }

    def "LIST | should retrieve shifts with pagination via HTTP GET"() {
        when: "requesting shifts via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/shifts?size=5"), adminId.toString()), Map)

        then: "the response is 200 OK with content list"
        response.status == HttpStatus.OK
        Map body = response.body()
        body.content instanceof List
        body.content.size() >= 2
    }

    def "LIST | should reject unauthenticated GET /shifts with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to list shifts"
        client.exchange(HttpRequest.GET("/shifts"))

        then: "a 401 UNAUTHORIZED is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }
}
