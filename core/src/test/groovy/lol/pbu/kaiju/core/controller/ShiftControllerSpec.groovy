package lol.pbu.kaiju.core.controller


import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.exceptions.HttpStatusException
import jakarta.inject.Inject
import jakarta.validation.ValidationException
import lol.pbu.kaiju.core.domain.Location
import lol.pbu.kaiju.core.domain.Organization
import lol.pbu.kaiju.core.domain.Project
import lol.pbu.kaiju.core.domain.Shift
import lol.pbu.kaiju.core.repository.ShiftRepository
import spock.lang.Unroll

import java.sql.Timestamp
import java.time.OffsetDateTime

import static java.time.temporal.ChronoUnit.SECONDS
import static lol.pbu.kaiju.core.model.ProjectStatus.DRAFT
import static lol.pbu.kaiju.core.model.ProjectType.STANDARD

class ShiftControllerSpec extends BaseControllerSpec {

    @Inject
    ShiftRepository shiftRepository

    @Inject
    ShiftController shiftController

    private Project getRandomProject() {
        def projectRow = sql.firstRow("SELECT id, title FROM projects LIMIT 1")
        if (!projectRow) {
            throw new IllegalStateException("No projects found in database to link shift to.")
        }
        def org = new Organization(UUID.randomUUID(), "Dummy Org", null, null, [])
        new Project(projectRow.id as UUID, org, projectRow.title as String, "Desc", STANDARD, DRAFT, OffsetDateTime.now(), null, null, [], [])
    }

    private Location getRandomLocation() {
        def locationRow = sql.firstRow("SELECT id, name FROM locations LIMIT 1")
        if (!locationRow) {
            throw new IllegalStateException("No locations found in database to link shift to.")
        }
        new Location(locationRow.id as UUID, locationRow.name as String, "123 St", "City", null, null, "US", null)
    }

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid virtual shift"() {
        given: "a new valid virtual shift"
        def project = getRandomProject()
        def newShift = new Shift(null, project, true, null, OffsetDateTime.now(), OffsetDateTime.now().plusHours(2), [])

        when: "the shift is added"
        Shift saved = shiftController.addShift(newShift)

        then: "the shift is persisted with a generated ID"
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
    }

    def "CREATE | should successfully save a valid physical shift"() {
        given: "a new valid physical shift"
        def project = getRandomProject()
        def location = getRandomLocation()
        def newShift = new Shift(null,
                project,
                false, // isVirtual
                location, // location
                OffsetDateTime.now(),
                OffsetDateTime.now().plusHours(2),
                [])

        when: "the shift is added"
        Shift saved = shiftController.addShift(newShift)

        then: "the shift is persisted with a generated ID"
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
    }

    @Unroll
    @SuppressWarnings("GroovyAssignabilityCheck")
    def "CREATE | should fail to save shift with invalid data: #testCase"(String testCase, Closure<Shift> shiftCreator) {
        when: "an attempt is made to add a shift with invalid data"
        Shift shift = shiftCreator()
        shiftController.addShift(shift)

        then: "an exception is thrown"
        thrown(ValidationException)

        where:
        [testCase, shiftCreator] << {
            def validProject = { -> getRandomProject() }
            def validLocation = { -> getRandomLocation() }

            def invalidCases = [
                    [field: 'project', value: { -> null }, caseName: "Null Project"],
                    [field: 'startTime', value: { -> null }, caseName: "Null Start Time"],
                    [field: 'endTime', value: { -> null }, caseName: "Null End Time"],
                    [field     : 'isVirtual', value: { -> false }, locationValue: { ->
                        null
                    }, caseName: "Physical Shift with Null Location"],
                    [field     : 'isVirtual', value: { -> true }, locationValue: { ->
                        validLocation()
                    }, caseName: "Virtual Shift with Non-Null Location"]
            ]

            return invalidCases.collect { invalidCase ->
                [
                        invalidCase.caseName,
                        { ->
                            def proj = (invalidCase.field == 'project' ? invalidCase.value() : validProject()) as Project
                            def start = (invalidCase.field == 'startTime' ? invalidCase.value() : OffsetDateTime.now()) as OffsetDateTime
                            def end = (invalidCase.field == 'endTime' ? invalidCase.value() : OffsetDateTime.now().plusHours(2)) as OffsetDateTime
                            def isVirt = (invalidCase.field == 'isVirtual' ? invalidCase.value() : true) as Boolean
                            def loc = (invalidCase.containsKey('locationValue') ? invalidCase.locationValue() : null) as Location

                            new Shift(null, proj, isVirt, loc, start, end, [])
                        } as Closure<Shift>
                ]
            }
        }()
    }

    /********** READ Tests **********/

    @Unroll
    def "READ | should retrieve an existing shift by ID"() {
        given: "an existing shift ID from the database"
        def firstRow = sql.firstRow("SELECT id FROM shifts LIMIT 1")
        assert firstRow != null
        UUID id = firstRow.id as UUID

        when: "the shift is requested by its ID"
        def result = shiftController.getShift(id)

        then: "the correct shift is returned"
        verifyAll {
            result.isPresent()
            result.get().id() == id
        }
    }

    def "READ | should return empty for a non-existent shift ID"() {
        when: "a non-existent shift is requested"
        def result = shiftController.getShift(UUID.randomUUID())

        then: "the result is empty"
        !result.isPresent()
    }

    /********** UPDATE Tests **********/

    def "UPDATE | should successfully update an existing shift"() {
        given: "an existing shift's details"
        def shiftRow = sql.firstRow("SELECT id FROM shifts LIMIT 1")
        assert shiftRow != null
        UUID id = shiftRow.id as UUID
        def project = getRandomProject()
        def newStart = OffsetDateTime.now().plusDays(1)
        def newEnd = newStart.plusHours(4)
        def updateRequest = new Shift(null, project, true, null, newStart, newEnd, [])

        when: "the shift is updated"
        Shift updated = shiftController.updateShift(id, updateRequest)

        then: "the returned shift contains the updated data"
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

    def "UPDATE | should fail to update a non-existent shift"() {
        given: "a random non-existent ID and an update request"
        def nonExistentId = UUID.randomUUID()
        def project = getRandomProject()
        def updateRequest = new Shift(null, project, true, null, OffsetDateTime.now(), OffsetDateTime.now().plusHours(2), [])

        when: "an update is attempted"
        shiftController.updateShift(nonExistentId, updateRequest)

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }

    def "UPDATE | should handle update of non-existent shift gracefully when using no-look"() {
        given: "a random non-existent ID and an update request"
        def nonExistentId = UUID.randomUUID()
        def project = getRandomProject()
        def updateRequest = new Shift(null, project, true, null, OffsetDateTime.now(), OffsetDateTime.now().plusHours(2), [])

        when: "a no-look update is attempted"
        shiftController.updateShiftNoLook(nonExistentId, updateRequest)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing shift"() {
        given: "a new shift to be deleted"
        def project = getRandomProject()
        def tempShift = new Shift(null,
                project,
                true,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now().plusHours(2),
                [])
        def saved = shiftController.addShift(tempShift)
        UUID id = saved.id()
        assert shiftRepository.existsById(id)

        when: "the shift is deleted"
        shiftController.deleteShift(id)

        then: "the shift no longer exists in the repository or database"
        verifyAll {
            !shiftRepository.findById(id).isPresent()
            sql.firstRow("SELECT count(*) as count FROM shifts WHERE id = ?", [id]).count == 0
        }
    }

    def "DELETE | should fail to delete a non-existent shift"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a delete is attempted"
        shiftController.deleteShift(nonExistentId)

        then: "an exception is thrown indicating not found"
        def e = thrown(HttpStatusException)
        e.status.code == 404
    }

    def "DELETE | should handle deletion of non-existent shift gracefully when using no-look"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a no-look delete is attempted"
        shiftController.deleteShiftNoLook(nonExistentId)

        then: "no exception is thrown"
        noExceptionThrown()
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
}
