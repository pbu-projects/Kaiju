package lol.pbu.kaiju.service

import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.controller.BaseControllerSpec
import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.domain.Project
import lol.pbu.kaiju.domain.Shift
import lol.pbu.kaiju.domain.Tag
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.model.ProjectStatus
import lol.pbu.kaiju.model.ProjectType
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.model.VerificationStatus
import lol.pbu.kaiju.repository.BoundaryRepository
import lol.pbu.kaiju.repository.LocationRepository
import lol.pbu.kaiju.repository.PermissionQueryRepository
import lol.pbu.kaiju.repository.ProjectRepository
import lol.pbu.kaiju.repository.ShiftRepository
import lol.pbu.kaiju.repository.TagRepository
import lol.pbu.kaiju.repository.UserRepository
import lol.pbu.kaiju.security.AuthorizationService
import org.locationtech.jts.geom.GeometryFactory

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@MicronautTest(transactional = false)
class DefaultShiftServiceSpec extends BaseControllerSpec {

    @Inject
    ShiftService shiftService

    @Inject
    ShiftRepository shiftRepository

    @Inject
    ProjectRepository projectRepository

    @Inject
    LocationRepository locationRepository

    @Inject
    TagRepository tagRepository

    @Inject
    AuthorizationService authorizationService

    def "handles shift validation, project checks, location checks, tags, and queries"() {
        given: "an admin, standard user, org, locations, active and draft projects, and a tag"
        def admin = dataBuilder.user().asAdmin().persist()
        def standardUser = dataBuilder.user().asStandardUser().persist()
        def org = dataBuilder.organization().withName("Shift Edge Org").asPublic().verified().persist()
        def allowedLoc = dataBuilder.location()
                .withName("Allowed Loc")
                .withAddress("123 Main", "Denver", "CO", "80202", "US")
                .atPoint(-104.9903, 39.7392)
                .persist()
        def otherLoc = dataBuilder.location()
                .withName("Other Loc")
                .withAddress("456 Main", "Denver", "CO", "80202", "US")
                .atPoint(-104.9803, 39.7492)
                .persist()

        def activeProject = dataBuilder.project()
                .forOrg(org)
                .withTitle("Active Shift Project")
                .withDescription("Description long enough")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.ACTIVE)
                .atLocation(allowedLoc)
                .persist()
        def virtualProject = dataBuilder.project()
                .forOrg(org)
                .withTitle("Virtual Shift Project")
                .withDescription("Description long enough")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.ACTIVE)
                .persist()
        def draftProject = dataBuilder.project()
                .forOrg(org)
                .withTitle("Draft Shift Project")
                .withDescription("Description long enough")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.DRAFT)
                .persist()
        def deletedProject = dataBuilder.project()
                .forOrg(org)
                .withTitle("Deleted Shift Project")
                .withDescription("Description long enough")
                .withType(ProjectType.STANDARD)
                .withStatus(ProjectStatus.ACTIVE)
                .deleted(admin)
                .persist()

        def tag = dataBuilder.tag().withName("edge-tag-${UUID.randomUUID().toString().take(8)}").persist()

        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2)
        OffsetDateTime end = start.plusHours(2)

        when: "creating a shift with null project"
        shiftService.createShift(new Shift(null, null, true, null, start, end, []), admin.id())

        then:
        HttpStatusException nullProjEx = thrown()
        nullProjEx.status == HttpStatus.BAD_REQUEST

        when: "creating a shift with unsaved project having null ID"
        Project unsavedProj = Project.forCreation(org, null, "Unsaved", "Desc", ProjectType.STANDARD, ProjectStatus.ACTIVE, [], [])
        shiftService.createShift(new Shift(null, unsavedProj, true, null, start, end, []), admin.id())

        then:
        HttpStatusException unsavedProjEx = thrown()
        unsavedProjEx.status == HttpStatus.BAD_REQUEST

        when: "creating a shift on a missing project as admin"
        shiftService.createShift(new Shift(null, Project.forId(UUID.randomUUID()), true, null, start, end, []), admin.id())

        then:
        HttpStatusException missingProjAdminEx = thrown()
        missingProjAdminEx.status == HttpStatus.NOT_FOUND

        when: "creating a shift on a missing project as standard user"
        shiftService.createShift(new Shift(null, Project.forId(UUID.randomUUID()), true, null, start, end, []), standardUser.id())

        then:
        HttpStatusException missingProjUserEx = thrown()
        missingProjUserEx.status == HttpStatus.FORBIDDEN

        when: "creating a shift on a soft-deleted project"
        shiftService.createShift(new Shift(null, deletedProject, true, null, start, end, []), admin.id())

        then:
        HttpStatusException deletedProjEx = thrown()
        deletedProjEx.status == HttpStatus.NOT_FOUND

        when: "creating a shift on a DRAFT project"
        shiftService.createShift(new Shift(null, draftProject, true, null, start, end, []), admin.id())

        then:
        HttpStatusException draftProjEx = thrown()
        draftProjEx.status == HttpStatus.BAD_REQUEST

        when: "creating a shift with start time after end time"
        shiftService.createShift(new Shift(null, activeProject, true, null, end, start, []), admin.id())

        then:
        HttpStatusException badTimeEx = thrown()
        badTimeEx.status == HttpStatus.BAD_REQUEST

        when: "creating a shift with an unsaved location whose ID is null"
        Location unsavedLoc = Location.forCreation("Unsaved", "123 St", "Denver", "CO", "80202", "US", null)
        shiftService.createShift(new Shift(null, activeProject, false, unsavedLoc, start, end, []), admin.id())

        then:
        HttpStatusException nullLocIdEx = thrown()
        nullLocIdEx.status == HttpStatus.BAD_REQUEST

        when: "creating a shift with a location that does not belong to the project"
        shiftService.createShift(new Shift(null, activeProject, false, otherLoc, start, end, []), admin.id())

        then:
        HttpStatusException wrongLocEx = thrown()
        wrongLocEx.status == HttpStatus.BAD_REQUEST

        when: "creating a shift on a virtual project with a missing location ID"
        shiftService.createShift(new Shift(null, virtualProject, false, Location.forId(UUID.randomUUID()), start, end, []), admin.id())

        then:
        HttpStatusException missingLocEx = thrown()
        missingLocEx.status == HttpStatus.NOT_FOUND

        when: "creating a shift with unsaved tags (null IDs only)"
        Shift shiftWithNullTag = track(shiftService.createShift(
                new Shift(null, activeProject, false, allowedLoc, start, end, [new Tag(null, "Unsaved Tag")]),
                admin.id()
        ))

        and: "creating a virtual shift with null tags and a physical shift with persisted tags"
        Shift virtualShift = track(shiftService.createShift(
                new Shift(null, virtualProject, true, null, start, end, null),
                admin.id()
        ))
        Shift taggedShift = track(shiftService.createShift(
                new Shift(null, activeProject, false, allowedLoc, start, end, [tag]),
                admin.id()
        ))

        then: "all shifts persist cleanly and query endpoints return them"
        def allPage = shiftService.getShifts(CursoredPageable.from(20, Sort.UNSORTED))
        def activePage = shiftService.getActiveShifts(CursoredPageable.from(20, Sort.UNSORTED))
        def foundShift = shiftService.getShiftById(taggedShift.id())
        verifyAll {
            shiftWithNullTag.id() != null
            virtualShift.id() != null
            taggedShift.id() != null
            !allPage.content.isEmpty()
            !activePage.content.isEmpty()
            foundShift.isPresent()
            foundShift.get().id() == taggedShift.id()
        }

        when: "updating and deleting a missing shift as admin"
        shiftService.updateShift(UUID.randomUUID(), virtualShift, admin.id())

        then:
        HttpStatusException updateMissingEx = thrown()
        updateMissingEx.status == HttpStatus.NOT_FOUND

        when:
        shiftService.deleteShift(UUID.randomUUID(), admin.id())

        then:
        HttpStatusException deleteMissingEx = thrown()
        deleteMissingEx.status == HttpStatus.NOT_FOUND
    }

    def "handles project locations list checks, null shift times, and concurrent delete race in unit tests"() {
        given: "a DefaultShiftService with mocked repositories"
        ShiftRepository mockShiftRepo = Mock(ShiftRepository)
        ProjectRepository mockProjectRepo = Mock(ProjectRepository)
        LocationRepository mockLocationRepo = Mock(LocationRepository)
        TagRepository mockTagRepo = Mock(TagRepository)
        UserRepository mockUserRepo = Mock(UserRepository)
        AuthorizationService unitAuth = new AuthorizationService(
                Mock(PermissionQueryRepository),
                mockUserRepo,
                mockLocationRepo,
                Mock(BoundaryRepository),
                new GeometryFactory()
        )

        DefaultShiftService unitService = new DefaultShiftService(
                mockShiftRepo,
                mockProjectRepo,
                mockLocationRepo,
                mockTagRepo,
                unitAuth
        )

        UUID actorId = UUID.randomUUID()
        UUID projectId = UUID.randomUUID()
        UUID allowedLocId = UUID.randomUUID()
        UUID foreignLocId = UUID.randomUUID()
        UUID shiftId = UUID.randomUUID()

        User admin = new User(actorId, "admin@example.com", UserRole.GLOBAL_ADMIN, OffsetDateTime.now(ZoneOffset.UTC))
        Organization org = new Organization(UUID.randomUUID(), "Org", null, null, true, VerificationStatus.VERIFIED, null, [])
        Location allowedLoc = new Location(allowedLocId, "Allowed", "123 St", "Denver", "CO", "80202", "US", null, "America/Denver")
        Location foreignLoc = new Location(foreignLocId, "Foreign", "456 St", "Denver", "CO", "80202", "US", null, "America/Denver")
        Project projectWithLocations = new Project(
                projectId, org, null, "Proj", "Desc", ProjectType.STANDARD, ProjectStatus.ACTIVE,
                OffsetDateTime.now(ZoneOffset.UTC), null, null, [allowedLoc], []
        )

        mockUserRepo.findById(actorId) >> Optional.of(admin)
        mockProjectRepo.findById(projectId) >> Optional.of(projectWithLocations)
        mockLocationRepo.findById(allowedLocId) >> Optional.of(allowedLoc)
        mockShiftRepo.save(_ as Shift) >> { Shift s -> s.withId(shiftId) }

        when: "creating a shift with a location present in project.locations() and null start/end times"
        Shift created = unitService.createShift(new Shift(null, projectWithLocations, false, allowedLoc, null, null, []), actorId)

        then: "location is accepted from project.locations() without extra location existence queries"
        created.id() == shiftId
        0 * mockProjectRepo.hasAnyProjectLocation(_)

        when: "creating a shift with startTime non-null and endTime null"
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)
        Shift startOnly = unitService.createShift(new Shift(null, projectWithLocations, false, allowedLoc, start, null, []), actorId)

        then:
        startOnly.id() == shiftId

        when: "creating a shift with a location not in project.locations()"
        unitService.createShift(new Shift(null, projectWithLocations, false, foreignLoc, start, start.plusHours(1), []), actorId)

        then:
        HttpStatusException wrongLocEx = thrown()
        wrongLocEx.status == HttpStatus.BAD_REQUEST

        when: "deleteShift finds an existing shift whose removeById returns 0"
        Shift existing = new Shift(shiftId, projectWithLocations, true, null, start, start.plusHours(1), [])
        mockShiftRepo.findById(shiftId) >> Optional.of(existing)
        mockShiftRepo.removeById(shiftId) >> 0L
        unitService.deleteShift(shiftId, actorId)

        then:
        HttpStatusException raceDeleteEx = thrown()
        raceDeleteEx.status == HttpStatus.NOT_FOUND
    }
}
