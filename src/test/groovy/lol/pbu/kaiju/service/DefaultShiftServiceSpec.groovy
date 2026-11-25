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
import spock.lang.Unroll

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

    private Map createShiftTestContext() {
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

        [
                admin         : admin,
                standardUser  : standardUser,
                org           : org,
                allowedLoc    : allowedLoc,
                otherLoc      : otherLoc,
                activeProject : activeProject,
                virtualProject: virtualProject,
                draftProject  : draftProject,
                deletedProject: deletedProject
        ]
    }

    private Map createMockShiftHarness() {
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

        [
                unitService         : unitService,
                mockShiftRepo       : mockShiftRepo,
                mockProjectRepo     : mockProjectRepo,
                actorId             : actorId,
                shiftId             : shiftId,
                allowedLoc          : allowedLoc,
                foreignLoc          : foreignLoc,
                projectWithLocations: projectWithLocations
        ]
    }

    @Unroll
    def "createShift fails with #expectedStatus when #scenario"() {
        given: "test users, projects, and locations"
        def ctx = createShiftTestContext()
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2)
        OffsetDateTime end = start.plusHours(2)

        UUID actorId = asStandardUser ? ctx.standardUser.id() : ctx.admin.id()
        Project project = switch (projectCase) {
            case "null" -> null
            case "unsaved" -> Project.forCreation(ctx.org, null, "Unsaved", "Desc", ProjectType.STANDARD, ProjectStatus.ACTIVE, [], [])
            case "missing" -> Project.forId(UUID.randomUUID())
            case "deleted" -> ctx.deletedProject
            case "draft" -> ctx.draftProject
            case "virtual" -> ctx.virtualProject
            default -> ctx.activeProject
        }
        Location location = switch (locationCase) {
            case "unsaved" -> Location.forCreation("Unsaved", "123 St", "Denver", "CO", "80202", "US", null)
            case "other" -> ctx.otherLoc
            case "missing" -> Location.forId(UUID.randomUUID())
            default -> null
        }
        OffsetDateTime shiftStart = swapTimes ? end : start
        OffsetDateTime shiftEnd = swapTimes ? start : end

        when: "creating the invalid shift"
        shiftService.createShift(new Shift(null, project, virtualFlag, location, shiftStart, shiftEnd, []), actorId)

        then: "the expected HTTP error is thrown"
        HttpStatusException ex = thrown()
        ex.status == expectedStatus

        where:
        scenario                                    | projectCase | asStandardUser | virtualFlag | locationCase | swapTimes || expectedStatus
        "project is null"                           | "null"      | false          | true        | "none"       | false     || HttpStatus.BAD_REQUEST
        "project is unsaved with null ID"           | "unsaved"   | false          | true        | "none"       | false     || HttpStatus.BAD_REQUEST
        "project is missing as admin"               | "missing"   | false          | true        | "none"       | false     || HttpStatus.NOT_FOUND
        "project is missing as standard user"       | "missing"   | true           | true        | "none"       | false     || HttpStatus.FORBIDDEN
        "project is soft-deleted"                   | "deleted"   | false          | true        | "none"       | false     || HttpStatus.NOT_FOUND
        "project is in DRAFT status"                | "draft"     | false          | true        | "none"       | false     || HttpStatus.BAD_REQUEST
        "start time is after end time"              | "active"    | false          | true        | "none"       | true      || HttpStatus.BAD_REQUEST
        "location is unsaved with null ID"          | "active"    | false          | false       | "unsaved"    | false     || HttpStatus.BAD_REQUEST
        "location does not belong to the project"   | "active"    | false          | false       | "other"      | false     || HttpStatus.BAD_REQUEST
        "location ID is missing on virtual project" | "virtual"   | false          | false       | "missing"    | false     || HttpStatus.NOT_FOUND
    }

    @Unroll
    def "createShift persists #scenario"() {
        given: "an admin user, active projects, location, and tag"
        def ctx = createShiftTestContext()
        def tag = dataBuilder.tag().withName("edge-tag-${UUID.randomUUID().toString().take(8)}").persist()
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2)
        OffsetDateTime end = start.plusHours(2)

        Project project = virtualFlag ? ctx.virtualProject : ctx.activeProject
        Location location = virtualFlag ? null : ctx.allowedLoc
        List<Tag> tags = switch (tagCase) {
            case "unsaved" -> [new Tag(null, "Unsaved Tag")]
            case "persisted" -> [tag]
            default -> null
        }

        when: "creating the shift"
        Shift created = track(shiftService.createShift(
                new Shift(null, project, virtualFlag, location, start, end, tags),
                ctx.admin.id()
        ))

        then: "the shift is saved with an ID"
        created.id() != null

        where:
        scenario                               | virtualFlag | tagCase
        "a physical shift with unsaved tags"   | false       | "unsaved"
        "a virtual shift with null tags"       | true        | "null"
        "a physical shift with persisted tags" | false       | "persisted"
    }

    private Shift createSavedShiftForQuery() {
        def ctx = createShiftTestContext()
        def tag = dataBuilder.tag().withName("query-tag-${UUID.randomUUID().toString().take(8)}").persist()
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2)
        OffsetDateTime end = start.plusHours(2)
        return track(shiftService.createShift(
                new Shift(null, ctx.activeProject, false, ctx.allowedLoc, start, end, [tag]),
                ctx.admin.id()
        ))
    }

    def "lists all shifts including a newly saved shift"() {
        given: "a saved shift"
        createSavedShiftForQuery()

        when: "querying all shifts"
        def allPage = shiftService.getShifts(CursoredPageable.from(20, Sort.UNSORTED))

        then: "the page contains shifts"
        !allPage.content.isEmpty()
    }

    def "lists active shifts including a newly saved shift"() {
        given: "a saved shift"
        createSavedShiftForQuery()

        when: "querying active shifts"
        def activePage = shiftService.getActiveShifts(CursoredPageable.from(20, Sort.UNSORTED))

        then: "the page contains active shifts"
        !activePage.content.isEmpty()
    }

    def "finds a saved shift by its ID"() {
        given: "a saved shift"
        Shift saved = createSavedShiftForQuery()

        when: "querying the shift by ID"
        def foundShift = shiftService.getShiftById(saved.id())

        then: "the saved shift is returned"
        verifyAll {
            foundShift.isPresent()
            foundShift.get().id() == saved.id()
        }
    }

    def "updateShift throws NOT_FOUND when the shift does not exist"() {
        given: "an admin user and a shift payload"
        def ctx = createShiftTestContext()
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2)
        Shift payload = new Shift(null, ctx.virtualProject, true, null, start, start.plusHours(2), [])

        when: "updating a non-existent shift"
        shiftService.updateShift(UUID.randomUUID(), payload, ctx.admin.id())

        then: "NOT_FOUND is thrown"
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.NOT_FOUND
    }

    def "deleteShift throws NOT_FOUND when the shift does not exist"() {
        given: "an admin user"
        def admin = dataBuilder.user().asAdmin().persist()

        when: "deleting a non-existent shift"
        shiftService.deleteShift(UUID.randomUUID(), admin.id())

        then: "NOT_FOUND is thrown"
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.NOT_FOUND
    }

    @Unroll
    def "createShift accepts project.locations() without extra location checks when #scenario"() {
        given: "a DefaultShiftService with mocked repositories and a project containing allowedLoc"
        def h = createMockShiftHarness()
        OffsetDateTime startTime = includeStart ? OffsetDateTime.now(ZoneOffset.UTC).plusDays(1) : null

        when: "creating a shift with a location in project.locations()"
        Shift created = h.unitService.createShift(
                new Shift(null, h.projectWithLocations, false, h.allowedLoc, startTime, null, []),
                h.actorId
        )

        then: "shift is saved without calling hasAnyProjectLocation"
        created.id() == h.shiftId
        0 * h.mockProjectRepo.hasAnyProjectLocation(_)

        where:
        scenario                              | includeStart
        "startTime and endTime are both null" | false
        "startTime is set and endTime is null"| true
    }

    def "createShift rejects a location not in project.locations()"() {
        given: "a DefaultShiftService with mocked repositories and a project containing allowedLoc"
        def h = createMockShiftHarness()
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)

        when: "creating a shift with a foreign location"
        h.unitService.createShift(
                new Shift(null, h.projectWithLocations, false, h.foreignLoc, start, start.plusHours(1), []),
                h.actorId
        )

        then: "BAD_REQUEST is thrown"
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.BAD_REQUEST
    }

    def "deleteShift throws NOT_FOUND when removeById deletes zero rows"() {
        given: "a DefaultShiftService with mocked repositories where shift exists but removeById returns 0"
        def h = createMockShiftHarness()
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)
        Shift existing = new Shift(h.shiftId, h.projectWithLocations, true, null, start, start.plusHours(1), [])
        h.mockShiftRepo.findById(h.shiftId) >> Optional.of(existing)
        h.mockShiftRepo.removeById(h.shiftId) >> 0L

        when: "deleting the shift"
        h.unitService.deleteShift(h.shiftId, h.actorId)

        then: "NOT_FOUND is thrown"
        HttpStatusException ex = thrown()
        ex.status == HttpStatus.NOT_FOUND
    }
}
