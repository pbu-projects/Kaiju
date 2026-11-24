package lol.pbu.kaiju.controller

import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.domain.Project
import lol.pbu.kaiju.domain.Shift
import lol.pbu.kaiju.dto.CreateShiftCommand
import lol.pbu.kaiju.dto.UpdateShiftCommand
import lol.pbu.kaiju.fixtures.TestPersonas
import lol.pbu.kaiju.model.ProjectStatus
import lol.pbu.kaiju.repository.LocationRepository
import lol.pbu.kaiju.repository.ProjectRepository
import lol.pbu.kaiju.repository.ShiftRepository
import spock.lang.Unroll

import java.time.OffsetDateTime
import java.util.UUID

import static java.time.temporal.ChronoUnit.SECONDS

@MicronautTest(transactional = false)
class ShiftControllerSpec extends BaseControllerSpec {

    @Inject
    ShiftRepository shiftRepository

    @Inject
    ProjectRepository projectRepository

    @Inject
    LocationRepository locationRepository

    @Inject
    ShiftController shiftController

    def setup() {
        ensureFixtures()
    }

    private void ensureFixtures() {
        if (locationRepository.count() == 0) {
            dataBuilder.location()
                    .withName("Default Shift Loc")
                    .withAddressLine("123 Main")
                    .withCity("Denver")
                    .withStateProvince("CO")
                    .withPostalCode("80202")
                    .withCountryCode("US")
                    .atPoint(-104.9903d, 39.7392d)
                    .persist()
        }
        UUID projectId = getRandomProject().id()
        if (shiftRepository.countByProjectStatusAndProjectDeletedAtIsNull(ProjectStatus.ACTIVE) == 0) {
            dataBuilder.shift()
                    .forProject(projectId)
                    .asVirtual()
                    .persist()
        }
    }

    private Project getRandomProject() {
        def projects = projectRepository.findByStatusAndDeletedAtIsNull(
                ProjectStatus.ACTIVE,
                CursoredPageable.from(1, Sort.UNSORTED)
        ).content
        if (projects.isEmpty()) {
            return dataBuilder.project()
                    .withTitle("Shift Test Proj")
                    .withDescription("Test project description exceeding 20 chars")
                    .withStatus(ProjectStatus.ACTIVE)
                    .persist()
        }
        return projects.first()
    }

    private Location getRandomLocation() {
        def locs = locationRepository.findAll(CursoredPageable.from(1, Sort.UNSORTED)).content
        if (locs.isEmpty()) {
            return dataBuilder.location()
                    .withName("Default Shift Loc")
                    .withAddressLine("123 Main")
                    .withCity("Denver")
                    .withStateProvince("CO")
                    .withPostalCode("80202")
                    .withCountryCode("US")
                    .atPoint(-104.9903d, 39.7392d)
                    .persist()
        }
        return locs.first()
    }

    private UUID getAnyShiftId() {
        def shifts = shiftRepository.findByProjectStatusAndProjectDeletedAtIsNull(
                ProjectStatus.ACTIVE,
                CursoredPageable.from(1, Sort.UNSORTED)
        ).content
        if (shifts.isEmpty()) {
            def shift = dataBuilder.shift()
                    .forProject(getRandomProject())
                    .asVirtual()
                    .persist()
            return shift.id()
        }
        return shifts.first().id()
    }

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid virtual shift when called by admin"() {
        given: "a new valid virtual shift command"
        def project = getRandomProject()
        def startTime = OffsetDateTime.now()
        def endTime = startTime.plusHours(2)
        def command = new CreateShiftCommand(project.id(), true, null, startTime, endTime, null)

        when: "the shift is added by admin via HTTP POST"
        def response = client.exchange(asGlobalAdmin(HttpRequest.POST("/shifts", command)), Shift)
        Shift saved = track(response.body())

        then: "the response is 200 OK and shift is persisted with a generated ID"
        verifyAll {
            response.status == HttpStatus.OK
            saved.id() != null
            saved.project().id() == project.id()
            saved.isVirtual()
            saved.location() == null
        }

        and: "it can be retrieved from the database"
        def result = shiftRepository.findById(saved.id()).orElse(null)
        verifyAll(result) {
            it != null
            it.id() == saved.id()
            it.project().id() == project.id()
            it.isVirtual() == true
            it.location() == null
        }
    }

    def "CREATE | should successfully save a valid physical shift when called by admin"() {
        given: "a new valid physical shift command"
        def location = getRandomLocation()
        def project = dataBuilder.project()
                .withTitle("Physical Shift Test Proj")
                .withDescription("Test project description exceeding 20 chars")
                .withStatus(ProjectStatus.ACTIVE)
                .atLocation(location)
                .persist()
        def startTime = OffsetDateTime.now()
        def endTime = startTime.plusHours(2)
        def command = new CreateShiftCommand(project.id(), false, location.id(), startTime, endTime, null)

        when: "the shift is added by admin via HTTP POST"
        def response = client.exchange(asGlobalAdmin(HttpRequest.POST("/shifts", command)), Shift)
        Shift saved = track(response.body())

        then: "the response is 200 OK and shift is persisted with a generated ID"
        verifyAll {
            response.status == HttpStatus.OK
            saved.id() != null
            saved.project().id() == project.id()
            (!saved.isVirtual())
            saved.location().id() == location.id()
        }

        and: "it can be retrieved from the database"
        def result = shiftRepository.findById(saved.id()).orElse(null)
        verifyAll(result) {
            it != null
            it.id() == saved.id()
            it.project().id() == project.id()
            it.isVirtual() == false
            it.location().id() == location.id()
        }
    }

    def "CREATE | should succeed when called by org manager of project organization"() {
        given: "a project belonging to Denver Metro Branch where Laura is Org Manager"
        def project = dataBuilder.project()
                .forOrg(TestPersonas.DENVER_METRO_BRANCH_ID)
                .withTitle("Laura Managed Proj")
                .withDescription("Project managed by Laura exceeding 20 chars")
                .persist()
        UUID projectId = project.id()
        def command = new CreateShiftCommand(projectId, true, null, OffsetDateTime.now(), OffsetDateTime.now().plusHours(2), null)

        when: "the org manager adds shift via HTTP POST"
        def response = client.exchange(asOrgManager(HttpRequest.POST("/shifts", command), TestPersonas.LAURA_LOCAL_MANAGER), Shift)
        Shift saved = track(response.body())

        then: "the shift is persisted with 200 OK"
        verifyAll {
            response.status == HttpStatus.OK
            saved.id() != null
        }
    }

    def "CREATE | should throw 403 Forbidden when standard user attempts to add shift for unmanaged project"() {
        given: "a standard user and a project"
        def project = getRandomProject()
        def command = new CreateShiftCommand(project.id(), true, null, OffsetDateTime.now(), OffsetDateTime.now().plusHours(2), null)

        when: "the standard user attempts to add shift via HTTP POST"
        client.exchange(asVolunteer(HttpRequest.POST("/shifts", command), TestPersonas.VICTOR_VOLUNTEER), Shift)

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "CREATE | should batch resolve tags and hide location existence oracle from unauthorized callers"() {
        given: "an active project, two persisted tags, and an unauthorized volunteer"
        def project = dataBuilder.project()
                .forOrg(TestPersonas.DENVER_METRO_BRANCH_ID)
                .withTitle("Shift Tag And Oracle Proj ${UUID.randomUUID()}")
                .withDescription("Active project used to test tag batching and location oracle.")
                .withStatus(ProjectStatus.ACTIVE)
                .persist()
        def tag1 = dataBuilder.tag().withName("shift-tag-a-${UUID.randomUUID().toString().take(8)}").persist()
        def tag2 = dataBuilder.tag().withName("shift-tag-b-${UUID.randomUUID().toString().take(8)}").persist()
        def start = OffsetDateTime.now().plusDays(1)
        def end = start.plusHours(2)

        when: "an authorized manager creates a virtual shift with two tags"
        def createCommand = new CreateShiftCommand(project.id(), true, null, start, end, [tag1.id(), tag2.id()])
        def response = client.exchange(
                asOrgManager(HttpRequest.POST("/shifts", createCommand), TestPersonas.LAURA_LOCAL_MANAGER),
                Shift
        )
        Shift saved = track(response.body())

        then: "both tags are attached cleanly"
        verifyAll(saved) {
            response.status == HttpStatus.OK
            tags() != null
            tags()*.id().toSet() == [tag1.id(), tag2.id()].toSet()
        }

        when: "an outsider tries to create a shift referencing a non-existent location"
        def oracleCommand = new CreateShiftCommand(project.id(), false, UUID.randomUUID(), start, end, null)
        client.exchange(asVolunteer(HttpRequest.POST("/shifts", oracleCommand), TestPersonas.VICTOR_VOLUNTEER), Shift)

        then: "403 FORBIDDEN is returned instead of leaking 404 for the location"
        def outsiderErr = thrown(HttpClientResponseException)
        outsiderErr.status == HttpStatus.FORBIDDEN
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
        client.exchange(asGlobalAdmin(HttpRequest.POST("/shifts", command)), Shift)

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
        client.exchange(asGlobalAdmin(HttpRequest.POST("/shifts", command)), Shift)

        then: "a 404 Not Found is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "CREATE | should return 403 Forbidden when outsider targets non-existent or soft-deleted project"() {
        given: "a soft-deleted project and a non-existent project ID"
        def deletedProject = dataBuilder.project()
                .forOrg(TestPersonas.DENVER_METRO_BRANCH_ID)
                .withTitle("Deleted Shift Oracle Proj ${UUID.randomUUID()}")
                .withDescription("Soft-deleted project used to verify outsiders receive 403 instead of 404.")
                .withStatus(ProjectStatus.ACTIVE)
                .deleted()
                .persist()
        def start = OffsetDateTime.now().plusDays(1)
        def end = start.plusHours(2)

        when: "an unrelated volunteer tries to add a shift to a non-existent project"
        client.exchange(
                asVolunteer(HttpRequest.POST("/shifts", new CreateShiftCommand(UUID.randomUUID(), true, null, start, end, null)), TestPersonas.VICTOR_VOLUNTEER),
                Shift
        )

        then: "403 Forbidden is returned"
        def missingErr = thrown(HttpClientResponseException)
        missingErr.status == HttpStatus.FORBIDDEN

        when: "an unrelated volunteer tries to add a shift to the soft-deleted project"
        client.exchange(
                asVolunteer(HttpRequest.POST("/shifts", new CreateShiftCommand(deletedProject.id(), true, null, start, end, null)), TestPersonas.VICTOR_VOLUNTEER),
                Shift
        )

        then: "403 Forbidden is returned"
        def deletedErr = thrown(HttpClientResponseException)
        deletedErr.status == HttpStatus.FORBIDDEN
    }

    /********** READ Tests **********/

    def "READ | should retrieve an existing shift by ID"() {
        given: "an existing shift ID from the database"
        UUID id = getAnyShiftId()

        when: "the shift is requested by its ID via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/shifts/${id}")), Shift)

        then: "the correct shift is returned with 200 OK"
        verifyAll(response) {
            status == HttpStatus.OK
            body().id() == id
        }
    }

    def "READ | should return 403 Forbidden when standard user requests unauthorized draft or pending shift"() {
        given: "a shift belonging to a pending project"
        def pendingProj = dataBuilder.project()
                .forOrg(TestPersonas.DENVER_METRO_BRANCH_ID)
                .withTitle("Pending Shift Read ${UUID.randomUUID()}")
                .withDescription("Pending project whose shifts must be hidden from outsiders.")
                .withStatus(ProjectStatus.PENDING)
                .persist()
        def pendingShift = dataBuilder.shift()
                .forProject(pendingProj)
                .asVirtual()
                .persist()

        when: "an unrelated volunteer requests GET /shifts/{id}"
        client.exchange(
                asVolunteer(HttpRequest.GET("/shifts/${pendingShift.id()}"), TestPersonas.VICTOR_VOLUNTEER),
                Shift
        )

        then: "403 Forbidden is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "READ | should return 404 Not Found for a non-existent shift ID"() {
        when: "a non-existent shift is requested via HTTP GET"
        client.exchange(asGlobalAdmin(HttpRequest.GET("/shifts/${UUID.randomUUID()}")), Shift)

        then: "a 404 NOT FOUND is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "READ | should reject unauthenticated GET /shifts/{id} with 401 UNAUTHORIZED"() {
        given: "an existing shift ID"
        UUID id = getAnyShiftId()

        when: "an unauthenticated caller requests a shift"
        client.exchange(HttpRequest.GET("/shifts/${id}"), Shift)

        then: "a 401 UNAUTHORIZED is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }

    /********** UPDATE Tests **********/

    def "UPDATE | should successfully update an existing shift when called by admin"() {
        given: "an existing shift's details"
        UUID id = getAnyShiftId()
        def newStart = OffsetDateTime.now().plusDays(1)
        def newEnd = newStart.plusHours(4)
        def updateCommand = new UpdateShiftCommand(true, null, newStart, newEnd, null)

        when: "the shift is updated by admin via HTTP PUT"
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/shifts/${id}", updateCommand)), Shift)
        Shift updated = response.body()

        then: "the response is 200 OK and contains updated data"
        verifyAll {
            response.status == HttpStatus.OK
            updated.id() == id
            updated.startTime() == newStart
            updated.endTime() == newEnd
        }

        and: "the changes are persisted in the database"
        def dbResult = shiftRepository.findById(id).orElse(null)
        verifyAll {
            dbResult != null
            dbResult.startTime().toInstant().truncatedTo(SECONDS) == newStart.toInstant().truncatedTo(SECONDS)
            dbResult.endTime().toInstant().truncatedTo(SECONDS) == newEnd.toInstant().truncatedTo(SECONDS)
        }
    }

    def "UPDATE | should throw 403 Forbidden when standard user attempts to update unmanaged shift"() {
        given: "an existing shift and an unauthorized user"
        UUID id = getAnyShiftId()
        def updateCommand = new UpdateShiftCommand(true, null, OffsetDateTime.now().plusDays(1), OffsetDateTime.now().plusDays(1).plusHours(2), null)

        when: "unauthorized user attempts to update shift via HTTP PUT"
        client.exchange(asVolunteer(HttpRequest.PUT("/shifts/${id}", updateCommand), TestPersonas.VICTOR_VOLUNTEER), Shift)

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "UPDATE | should reject unauthenticated PUT /shifts/{id} with 401 UNAUTHORIZED"() {
        given: "an existing shift ID and update command"
        UUID id = getAnyShiftId()
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
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/shifts/${nonExistentId}", updateCommand)), Shift)

        then: "a 404 NOT FOUND is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "UPDATE | should return 403 Forbidden when outsider targets non-existent shift or shift on deleted project"() {
        given: "a shift on a soft-deleted project"
        def deletedProject = dataBuilder.project()
                .forOrg(TestPersonas.DENVER_METRO_BRANCH_ID)
                .withTitle("Deleted Update Shift Proj ${UUID.randomUUID()}")
                .withDescription("Soft-deleted project with a shift for update oracle protection.")
                .withStatus(ProjectStatus.ACTIVE)
                .deleted()
                .persist()
        def deletedShift = dataBuilder.shift().forProject(deletedProject).asVirtual().persist()
        def start = OffsetDateTime.now().plusDays(1)
        def end = start.plusHours(2)
        def updateCommand = new UpdateShiftCommand(true, null, start, end, null)

        when: "an unrelated volunteer tries to update a non-existent shift"
        client.exchange(
                asVolunteer(HttpRequest.PUT("/shifts/${UUID.randomUUID()}", updateCommand), TestPersonas.VICTOR_VOLUNTEER),
                Shift
        )

        then: "403 Forbidden is returned"
        def missingErr = thrown(HttpClientResponseException)
        missingErr.status == HttpStatus.FORBIDDEN

        when: "an unrelated volunteer tries to update the shift on the soft-deleted project"
        client.exchange(
                asVolunteer(HttpRequest.PUT("/shifts/${deletedShift.id()}", updateCommand), TestPersonas.VICTOR_VOLUNTEER),
                Shift
        )

        then: "403 Forbidden is returned"
        def deletedErr = thrown(HttpClientResponseException)
        deletedErr.status == HttpStatus.FORBIDDEN

        when: "the owning org manager tries to update the shift on the soft-deleted project"
        client.exchange(
                asOrgManager(HttpRequest.PUT("/shifts/${deletedShift.id()}", updateCommand), TestPersonas.LAURA_LOCAL_MANAGER),
                Shift
        )

        then: "404 Not Found is returned with Shift not found"
        def managerErr = thrown(HttpClientResponseException)
        managerErr.status == HttpStatus.NOT_FOUND
    }

    @Unroll
    def "UPDATE | should throw 400 Bad Request on invalid location logic: #testCase"(String testCase, boolean isVirtual, UUID locationId) {
        given: "an existing shift and an update command with invalid location logic"
        UUID id = getAnyShiftId()
        def updateCommand = new UpdateShiftCommand(isVirtual, locationId, OffsetDateTime.now().plusDays(1), OffsetDateTime.now().plusDays(1).plusHours(2), null)

        when: "attempting to update shift via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/shifts/${id}", updateCommand)), Shift)

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
        def createResponse = client.exchange(asGlobalAdmin(HttpRequest.POST("/shifts", command)), Shift)
        UUID id = track(createResponse.body()).id()
        assert shiftRepository.existsById(id)

        when: "the shift is deleted by admin via HTTP DELETE"
        def response = client.exchange(asGlobalAdmin(HttpRequest.DELETE("/shifts/${id}")))

        then: "the response is 200 OK and shift no longer exists"
        verifyAll {
            response.status == HttpStatus.OK
            !shiftRepository.findById(id).isPresent()
            !shiftRepository.existsById(id)
        }
    }

    def "DELETE | should throw 403 Forbidden when standard user attempts to delete unmanaged shift"() {
        given: "an existing shift and an unauthorized user"
        UUID id = getAnyShiftId()

        when: "unauthorized user attempts to delete shift via HTTP DELETE"
        client.exchange(asVolunteer(HttpRequest.DELETE("/shifts/${id}"), TestPersonas.VICTOR_VOLUNTEER))

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "DELETE | should reject unauthenticated DELETE /shifts/{id} with 401 UNAUTHORIZED"() {
        given: "an existing shift ID"
        UUID id = getAnyShiftId()

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
        client.exchange(asGlobalAdmin(HttpRequest.DELETE("/shifts/${nonExistentId}")))

        then: "a 404 NOT FOUND is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "DELETE | should return 403 Forbidden when outsider targets non-existent shift or shift on deleted project"() {
        given: "a shift on a soft-deleted project"
        def deletedProject = dataBuilder.project()
                .forOrg(TestPersonas.DENVER_METRO_BRANCH_ID)
                .withTitle("Deleted Delete Shift Proj ${UUID.randomUUID()}")
                .withDescription("Soft-deleted project with a shift for delete oracle protection.")
                .withStatus(ProjectStatus.ACTIVE)
                .deleted()
                .persist()
        def deletedShift = dataBuilder.shift().forProject(deletedProject).asVirtual().persist()

        when: "an unrelated volunteer tries to delete a non-existent shift"
        client.exchange(asVolunteer(HttpRequest.DELETE("/shifts/${UUID.randomUUID()}"), TestPersonas.VICTOR_VOLUNTEER))

        then: "403 Forbidden is returned"
        def missingErr = thrown(HttpClientResponseException)
        missingErr.status == HttpStatus.FORBIDDEN

        when: "an unrelated volunteer tries to delete the shift on the soft-deleted project"
        client.exchange(asVolunteer(HttpRequest.DELETE("/shifts/${deletedShift.id()}"), TestPersonas.VICTOR_VOLUNTEER))

        then: "403 Forbidden is returned"
        def deletedErr = thrown(HttpClientResponseException)
        deletedErr.status == HttpStatus.FORBIDDEN

        when: "the owning org manager tries to delete the shift on the soft-deleted project"
        client.exchange(asOrgManager(HttpRequest.DELETE("/shifts/${deletedShift.id()}"), TestPersonas.LAURA_LOCAL_MANAGER))

        then: "404 Not Found is returned"
        def managerErr = thrown(HttpClientResponseException)
        managerErr.status == HttpStatus.NOT_FOUND
    }

    /********** LIST Tests **********/

    def "LIST | should fully drain all shifts sequentially using cursors"() {
        setup:
        UUID projectId = getRandomProject().id()
        while (shiftRepository.countByProjectStatusAndProjectDeletedAtIsNull(ProjectStatus.ACTIVE) < 2) {
            dataBuilder.shift().forProject(projectId).asVirtual().persist()
        }
        Set<Shift> allShifts = new LinkedHashSet<>()
        int pageSize = 5
        def pageable = CursoredPageable.from(pageSize, Sort.of(Sort.Order.asc("id")))

        when: "iterating through pages until no more data remains"
        while (pageable != null) {
            CursoredPage<Shift> page = shiftController.getShifts(pageable, null)
            allShifts.addAll(page.content)
            pageable = page.hasNext() ? page.nextPageable() : null
        }

        then: "the collected set contains all active non-deleted project shifts from the database"
        def totalCount = shiftRepository.countByProjectStatusAndProjectDeletedAtIsNull(ProjectStatus.ACTIVE)
        verifyAll {
            allShifts.size() == totalCount
            allShifts.size() >= 2
        }
    }

    def "LIST | should drain all non-deleted project shifts for global admin and exclude draft shifts for standard users"() {
        setup:
        def draftProj = dataBuilder.project()
                .withTitle("Draft Shift Visibility Proj")
                .withDescription("Draft project used to test shift cursor filtering.")
                .withStatus(ProjectStatus.DRAFT)
                .persist()
        def draftShift = dataBuilder.shift().forProject(draftProj).asVirtual().persist()
        def adminPrincipal = createPrincipal(TestPersonas.SARAH_ADMIN.id)
        def volunteerPrincipal = createPrincipal(TestPersonas.VICTOR_VOLUNTEER.id)

        when: "draining shifts as global admin and as standard user"
        Set<UUID> adminShiftIds = [] as LinkedHashSet
        def adminPageable = CursoredPageable.from(20, Sort.of(Sort.Order.asc("id")))
        while (adminPageable != null) {
            CursoredPage<Shift> page = shiftController.getShifts(adminPageable, adminPrincipal)
            adminShiftIds.addAll(page.content*.id())
            adminPageable = page.hasNext() ? page.nextPageable() : null
        }

        Set<UUID> volunteerShiftIds = [] as LinkedHashSet
        def volPageable = CursoredPageable.from(20, Sort.of(Sort.Order.asc("id")))
        while (volPageable != null) {
            CursoredPage<Shift> page = shiftController.getShifts(volPageable, volunteerPrincipal)
            volunteerShiftIds.addAll(page.content*.id())
            volPageable = page.hasNext() ? page.nextPageable() : null
        }

        then: "global admin sees all non-deleted project shifts including draft, while standard user only sees active ones"
        verifyAll {
            adminShiftIds.size() == shiftRepository.countByProjectDeletedAtIsNull()
            adminShiftIds.contains(draftShift.id())
            volunteerShiftIds.size() == shiftRepository.countByProjectStatusAndProjectDeletedAtIsNull(ProjectStatus.ACTIVE)
            !volunteerShiftIds.contains(draftShift.id())
        }
    }

    def "LIST | should retrieve shifts with pagination via HTTP GET"() {
        setup:
        UUID projectId = getRandomProject().id()
        while (shiftRepository.count() < 2) {
            dataBuilder.shift().forProject(projectId).asVirtual().persist()
        }

        when: "requesting shifts via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/shifts?size=5")), Map)

        then: "the response is 200 OK with content list"
        Map body = response.body()
        verifyAll {
            response.status == HttpStatus.OK
            body.content instanceof List
            body.content.size() >= 2
        }
    }

    def "LIST | should reject unauthenticated GET /shifts with 401 UNAUTHORIZED"() {
        when: "an unauthenticated caller attempts to list shifts"
        client.exchange(HttpRequest.GET("/shifts"))

        then: "a 401 UNAUTHORIZED is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED
    }
}
