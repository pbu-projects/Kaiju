package lol.pbu.kaiju.controller

import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.http.uri.UriBuilder
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.domain.Project
import lol.pbu.kaiju.dto.CoordinateDto
import lol.pbu.kaiju.dto.CreateProjectCommand
import lol.pbu.kaiju.dto.ProjectBoundaryCommand
import lol.pbu.kaiju.dto.ProjectLocationCommand
import lol.pbu.kaiju.dto.UpdateProjectCommand
import lol.pbu.kaiju.fixtures.TestPersonas
import lol.pbu.kaiju.model.ProjectStatus
import lol.pbu.kaiju.model.ProjectType
import lol.pbu.kaiju.repository.AdministrativeRegionRepository
import lol.pbu.kaiju.repository.OrganizationRepository
import lol.pbu.kaiju.repository.ProjectAuditLogRepository
import lol.pbu.kaiju.repository.ProjectRepository
import lol.pbu.kaiju.repository.UserRepository
import lol.pbu.kaiju.security.ProjectSecurityService
import net.datafaker.Faker
import org.locationtech.jts.geom.Coordinate
import spock.lang.Shared
import spock.lang.Unroll

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

import static lol.pbu.kaiju.model.ProjectStatus.ACTIVE
import static lol.pbu.kaiju.model.ProjectStatus.DRAFT
import static lol.pbu.kaiju.model.ProjectStatus.PENDING
import static lol.pbu.kaiju.model.ProjectStatus.PENDING_UPDATE
import static lol.pbu.kaiju.model.ProjectType.STANDARD

@MicronautTest(transactional = false)
class ProjectControllerSpec extends BaseControllerSpec {

    @Inject
    ProjectRepository projectRepository

    @Inject
    ProjectController projectController

    @Inject
    ProjectSecurityService realProjectSecurityService

    @Inject
    OrganizationRepository organizationRepository

    @Inject
    UserRepository userRepository

    @Inject
    ProjectAuditLogRepository projectAuditLogRepository

    @Inject
    AdministrativeRegionRepository administrativeRegionRepository

    @Shared
    Faker faker = new Faker()

    @Shared
    String adminId = TestPersonas.SARAH_ADMIN.id.toString()

    def setupSpec() {
        executeUpdate("INSERT INTO users (id, email, role) VALUES ('00000000-0000-0000-0000-000000000000', 'test-principal@example.com', 'GLOBAL_ADMIN') ON CONFLICT DO NOTHING")
    }

    private static final String SEEDED_REGION_IDS = TestPersonas.SEEDED_REGION_IDS.collect { "'${it}'" }.join(', ')
    private static final String SEEDED_ORG_IDS = TestPersonas.SEEDED_ORGANIZATION_IDS.collect { "'${it}'" }.join(', ')
    private static final String SEEDED_USER_IDS = TestPersonas.SEEDED_PERSONAS.collect { "'${it.id}'" }.join(', ')

    def cleanup() {
        executeUpdate("DELETE FROM shift_tags")
        executeUpdate("DELETE FROM shifts")
        executeUpdate("DELETE FROM project_audit_logs")
        executeUpdate("DELETE FROM project_locations")
        executeUpdate("DELETE FROM project_boundaries")
        executeUpdate("DELETE FROM project_users")
        executeUpdate("DELETE FROM projects")
        executeUpdate("DELETE FROM organization_regions WHERE organization_id NOT IN (${SEEDED_ORG_IDS})")
        executeUpdate("DELETE FROM organization_users WHERE user_id NOT IN (${SEEDED_USER_IDS}) OR organization_id NOT IN (${SEEDED_ORG_IDS})")
        executeUpdate("DELETE FROM region_users WHERE user_id NOT IN (${SEEDED_USER_IDS}) OR region_id NOT IN (${SEEDED_REGION_IDS})")
        executeUpdate("DELETE FROM locations WHERE name LIKE '%Loc%' OR name LIKE '%St%' OR address_line LIKE '%St%' OR city = 'Denver' OR city = 'Boulder' OR city = 'Toronto' OR city = 'Colo Springs'")
        executeUpdate("DELETE FROM administrative_regions WHERE id NOT IN (${SEEDED_REGION_IDS})")
        executeUpdate("DELETE FROM organizations WHERE (name LIKE '%Org%' OR name LIKE '%Denver%' OR name LIKE '%Boulder%' OR name LIKE '%Default%' OR name LIKE '%Test%') AND id NOT IN (${SEEDED_ORG_IDS})")
        executeUpdate("DELETE FROM users WHERE email LIKE '%@example.com' AND id != '00000000-0000-0000-0000-000000000000' AND id NOT IN (${SEEDED_USER_IDS})")
    }

    def setup() {
        cleanup()
    }

    private Organization getRandomOrganization() {
        return dataBuilder.organization().persist()
    }

    private List<ProjectBoundaryCommand> defaultBoundaries(String name = "Default Boundary") {
        [
                new ProjectBoundaryCommand(
                        name,
                        [
                                new CoordinateDto(-105.0, 39.0),
                                new CoordinateDto(-104.0, 39.0),
                                new CoordinateDto(-104.0, 40.0),
                                new CoordinateDto(-105.0, 39.0)
                        ]
                )
        ]
    }

    private CreateProjectCommand createProjectCommand(
            UUID orgId,
            String title = "Valid Project Title ${faker.number().digits(5)}",
            String description = "Valid Description that has at least 20 chars long.",
            ProjectType projectType = STANDARD,
            ProjectStatus status = DRAFT,
            UUID managingRegionId = null,
            List<ProjectLocationCommand> locations = [],
            List<ProjectBoundaryCommand> boundaries = defaultBoundaries()
    ) {
        dataBuilder.project()
                .forOrg(orgId)
                .inRegion(managingRegionId)
                .withTitle(title)
                .withDescription(description)
                .withType(projectType)
                .withStatus(status)
                .withCommandLocations(locations)
                .withCommandBoundaries(boundaries)
                .asCommand()
    }

    private UpdateProjectCommand updateProjectCommand(
            UUID orgId = null,
            String title = "Updated Project Title ${faker.number().digits(5)}",
            String description = "Updated Description that has at least 20 chars long.",
            ProjectType projectType = STANDARD,
            UUID managingRegionId = null,
            List<ProjectLocationCommand> locations = [],
            List<ProjectBoundaryCommand> boundaries = defaultBoundaries()
    ) {
        dataBuilder.project()
                .forOrg(orgId)
                .inRegion(managingRegionId)
                .withTitle(title)
                .withDescription(description)
                .withType(projectType)
                .withCommandLocations(locations)
                .withCommandBoundaries(boundaries)
                .asUpdateCommand()
    }

    /********** CREATE Tests **********/

    def "CREATE | should successfully save a valid project"() {
        given: "a valid create project command"
        def org = getRandomOrganization()
        def command = createProjectCommand(org.id(), "Test Project ${faker.company().name()}", "Test Description with plenty of words to exceed twenty characters limit.", STANDARD, DRAFT)

        when: "the project is added via HTTP POST"
        def response = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", command)), Project)
        Project saved = response.body()

        then: "the project is persisted with 200 OK and a generated ID"
        verifyAll {
            response.status == HttpStatus.OK
            saved.id() != null
            saved.title() == command.title()
            saved.description() == command.description()
            saved.projectType() == command.projectType()
            saved.status() == DRAFT
        }

        and: "it can be retrieved from the database"
        def result = sql.firstRow("SELECT * FROM projects WHERE id = ?", [saved.id()])
        verifyAll(result) {
            saved.id() == id
            saved.title() == title
            saved.description() == description
        }
    }

    def "CREATE | should ensure child locations and boundaries have server-generated IDs (Issue #26 mass-assignment immunity)"() {
        given: "a project create command with child locations and boundaries"
        def org = getRandomOrganization()
        def locCmd = new ProjectLocationCommand(
                "Mass Assign Loc",
                "123 St",
                "Denver",
                "CO",
                "80202",
                "US",
                -104.9903,
                39.7392
        )
        def bndCmd = new ProjectBoundaryCommand(
                "Mass Assign Bnd",
                [
                        new CoordinateDto(-105.0, 39.0),
                        new CoordinateDto(-104.0, 39.0),
                        new CoordinateDto(-104.0, 40.0),
                        new CoordinateDto(-105.0, 39.0)
                ]
        )
        def command = new CreateProjectCommand(
                org.id(),
                null,
                "Mass Assignment Project",
                "Description for mass assignment immunity test exceeding 20 chars.",
                STANDARD,
                DRAFT,
                [locCmd],
                [bndCmd]
        )

        when: "submitting the project via HTTP POST"
        def response = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", command)), Project)
        Project saved = response.body()

        then: "the project is persisted with generated ID"
        verifyAll {
            response.status == HttpStatus.OK
            saved.id() != null
        }

        and: "child locations and boundaries enforce mass-assignment immunity with server-managed null IDs"
        verifyAll {
            saved.locations() != null
            saved.locations().size() == 1
            saved.locations()[0].id() == null
            saved.locations()[0].name() == "Mass Assign Loc"
            saved.boundaries() != null
            saved.boundaries().size() == 1
            saved.boundaries()[0].id() == null
            saved.boundaries()[0].name() == "Mass Assign Bnd"
        }
    }

    def "CREATE | should fail when boundaries list is empty (Issue #41)"() {
        given: "a command with empty boundaries"
        def org = getRandomOrganization()
        def command = new CreateProjectCommand(
                org.id(),
                null,
                "Empty Boundaries Project",
                "Description with at least twenty characters here.",
                STANDARD,
                DRAFT,
                [],
                []
        )

        when: "submitting via HTTP POST"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", command)), Project)

        then: "a 400 Bad Request is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
    }

    def "CREATE | should fail when description is less than 20 characters (Issue #42)"() {
        given: "a command with description under 20 characters"
        def org = getRandomOrganization()
        def command = new CreateProjectCommand(
                org.id(),
                null,
                "Short Desc Proj",
                "Too short!",
                STANDARD,
                DRAFT,
                [],
                defaultBoundaries()
        )

        when: "submitting via HTTP POST"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", command)), Project)

        then: "a 400 Bad Request is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
    }

    @Unroll
    def "CREATE | should fail to save project with invalid payload: #testCase"(String testCase, Map payload) {
        when: "submitting invalid project data via HTTP POST"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", payload)), Project)

        then: "a 400 Bad Request exception is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        testCase                 | payload
        "Null Organization ID"   | [organizationId: null, title: "Valid Title", description: "At least 20 chars in description", projectType: "STANDARD", boundaries: [[name: "B", coordinates: [[longitude: -105.0, latitude: 39.0], [longitude: -104.0, latitude: 39.0], [longitude: -104.0, latitude: 40.0], [longitude: -105.0, latitude: 39.0]]]]]
        "Null Title"             | [organizationId: UUID.randomUUID(), title: null, description: "At least 20 chars in description", projectType: "STANDARD", boundaries: [[name: "B", coordinates: [[longitude: -105.0, latitude: 39.0], [longitude: -104.0, latitude: 39.0], [longitude: -104.0, latitude: 40.0], [longitude: -105.0, latitude: 39.0]]]]]
        "Blank Title"            | [organizationId: UUID.randomUUID(), title: "   ", description: "At least 20 chars in description", projectType: "STANDARD", boundaries: [[name: "B", coordinates: [[longitude: -105.0, latitude: 39.0], [longitude: -104.0, latitude: 39.0], [longitude: -104.0, latitude: 40.0], [longitude: -105.0, latitude: 39.0]]]]]
        "Title Too Long"         | [organizationId: UUID.randomUUID(), title: "A" * 256, description: "At least 20 chars in description", projectType: "STANDARD", boundaries: [[name: "B", coordinates: [[longitude: -105.0, latitude: 39.0], [longitude: -104.0, latitude: 39.0], [longitude: -104.0, latitude: 40.0], [longitude: -105.0, latitude: 39.0]]]]]
        "Null Description"       | [organizationId: UUID.randomUUID(), title: "Valid Title", description: null, projectType: "STANDARD", boundaries: [[name: "B", coordinates: [[longitude: -105.0, latitude: 39.0], [longitude: -104.0, latitude: 39.0], [longitude: -104.0, latitude: 40.0], [longitude: -105.0, latitude: 39.0]]]]]
        "Description Too Short"  | [organizationId: UUID.randomUUID(), title: "Valid Title", description: "Short", projectType: "STANDARD", boundaries: [[name: "B", coordinates: [[longitude: -105.0, latitude: 39.0], [longitude: -104.0, latitude: 39.0], [longitude: -104.0, latitude: 40.0], [longitude: -105.0, latitude: 39.0]]]]]
        "Null Project Type"      | [organizationId: UUID.randomUUID(), title: "Valid Title", description: "At least 20 chars in description", projectType: null, boundaries: [[name: "B", coordinates: [[longitude: -105.0, latitude: 39.0], [longitude: -104.0, latitude: 39.0], [longitude: -104.0, latitude: 40.0], [longitude: -105.0, latitude: 39.0]]]]]
        "Null Boundaries"        | [organizationId: UUID.randomUUID(), title: "Valid Title", description: "At least 20 chars in description", projectType: "STANDARD", boundaries: null]
        "Empty Boundaries"       | [organizationId: UUID.randomUUID(), title: "Valid Title", description: "At least 20 chars in description", projectType: "STANDARD", boundaries: []]
    }

    def "CREATE | should throw 400 when organization does not exist"() {
        given: "a create command with non-existent organization ID"
        def nonExistentOrgId = UUID.randomUUID()
        def command = createProjectCommand(nonExistentOrgId, "Orphan Project")

        when: "submitting via HTTP POST"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", command)), Project)

        then: "a 400 Bad Request is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
    }

    /********** READ Tests **********/

    def "READ | should retrieve an existing project by ID"() {
        given: "an existing project created via HTTP"
        def org = getRandomOrganization()
        def command = createProjectCommand(org.id(), "Test Project Read")
        Project created = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", command)), Project).body()
        UUID id = created.id()

        when: "the project is requested by its ID via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/projects/${id}")), Project)
        Project result = response.body()

        then: "the correct project is returned"
        verifyAll {
            response.status == HttpStatus.OK
            result != null
            result.id() == id
            result.title() == "Test Project Read"
        }
    }

    def "READ | should return 404 for a non-existent project ID"() {
        when: "a non-existent project is requested via HTTP GET"
        client.exchange(asGlobalAdmin(HttpRequest.GET("/projects/${UUID.randomUUID()}")), Project)

        then: "a 404 Not Found exception is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "READ | should retrieve projects by title"() {
        given: "an existing project in the database"
        def org = getRandomOrganization()
        def targetTitle = "Searchable Title ${faker.number().digits(5)}"
        def command = createProjectCommand(org.id(), targetTitle)
        client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", command)), Project)

        when: "projects are searched by this title via HTTP GET"
        def uri = UriBuilder.of("/projects").queryParam("title", targetTitle).build().toString()
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET(uri)), Map)

        then: "the search returns a page containing the project"
        def content = response.body().content as List
        verifyAll {
            response.status == HttpStatus.OK
            content != null
            content.any { it.title == targetTitle }
        }
    }

    /********** UPDATE Tests **********/

    def "UPDATE | should successfully update an existing project"() {
        given: "an existing project"
        def org = getRandomOrganization()
        def createCmd = createProjectCommand(org.id(), "Original Project Title", "Original Description with plenty of words to exceed 20 characters.", STANDARD, DRAFT)
        Project saved = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", createCmd)), Project).body()
        UUID id = saved.id()

        def newTitle = "Updated ${faker.book().title()}"
        def newDescription = "Updated Description with plenty of words to exceed twenty characters limit."
        def updateCmd = updateProjectCommand(org.id(), newTitle, newDescription, STANDARD)

        when: "the project is updated via HTTP PUT"
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/projects/${id}", updateCmd)), Project)
        Project updated = response.body()

        then: "the returned project contains updated data while status remains DRAFT"
        verifyAll {
            response.status == HttpStatus.OK
            updated.id() == id
            updated.title() == newTitle
            updated.description() == newDescription
            updated.status() == DRAFT
        }

        and: "the changes are persisted in the database"
        def dbResult = sql.firstRow("SELECT title, description, status FROM projects WHERE id = ?", [id])
        verifyAll(dbResult) {
            title == newTitle
            description == newDescription
            status == 'DRAFT'
        }
    }

    def "UPDATE | should fail to update a non-existent project"() {
        given: "a random non-existent ID and an update command"
        def nonExistentId = UUID.randomUUID()
        def org = getRandomOrganization()
        def updateCmd = updateProjectCommand(org.id(), "New Title", "New Description exceeding twenty chars length.")

        when: "an update is attempted via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/projects/${nonExistentId}", updateCmd)), Project)

        then: "a 404 Not Found exception is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "UPDATE | should fail when boundaries list is empty (Issue #41)"() {
        given: "an existing project"
        def org = getRandomOrganization()
        def createCmd = createProjectCommand(org.id(), "Project Before Empty Boundary Update")
        Project saved = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", createCmd)), Project).body()

        and: "an update command with empty boundaries"
        def updateCmd = new UpdateProjectCommand(
                org.id(),
                null,
                "Updated Title With Empty Boundaries",
                "Description with at least twenty characters here.",
                STANDARD,
                [],
                []
        )

        when: "updating via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/projects/${saved.id()}", updateCmd)), Project)

        then: "a 400 Bad Request is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
    }

    def "UPDATE | should fail when description is less than 20 characters (Issue #42)"() {
        given: "an existing project"
        def org = getRandomOrganization()
        def createCmd = createProjectCommand(org.id(), "Project Before Short Desc Update")
        Project saved = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", createCmd)), Project).body()

        and: "an update command with description under 20 characters"
        def updateCmd = new UpdateProjectCommand(
                org.id(),
                null,
                "Valid Updated Title",
                "Too short!",
                STANDARD,
                [],
                defaultBoundaries()
        )

        when: "updating via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/projects/${saved.id()}", updateCmd)), Project)

        then: "a 400 Bad Request is returned"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
    }

    def "UPDATE | should reject reassignment to another organization when user is ORG_MANAGER"() {
        given: "two organizations Org A and Org B"
        def orgAId = TestPersonas.DENVER_METRO_BRANCH_ID
        def orgBId = TestPersonas.UTAH_STATE_CHAPTER_ID

        and: "a project belonging to Org A"
        def project = dataBuilder.project()
                .forOrg(orgAId)
                .withTitle("Project in Org A")
                .withDescription("Description of project in Org A that is long enough.")
                .withType(STANDARD)
                .withStatus(DRAFT)
                .persist()
        def projectId = project.id()

        and: "an update command attempting to reassign the project to Org B"
        def updateCmd = updateProjectCommand(orgBId, "Hijacked Title", "Hijacked Description that is at least twenty chars long.")

        when: "the Org Manager attempts to reassign the project to Org B via HTTP PUT"
        client.exchange(asOrgManager(HttpRequest.PUT("/projects/${projectId}", updateCmd)), Project)

        then: "the request is rejected with 403 Forbidden"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN

        and: "the project in the database remains assigned to Org A"
        def projectInDb = sql.firstRow("SELECT organization_id, title FROM projects WHERE id = ?", [projectId])
        verifyAll(projectInDb) {
            organization_id == orgAId
            title == 'Project in Org A'
        }
    }

    def "UPDATE | should allow legitimate update by Org Manager preserving existing organization"() {
        given: "an organization Org A"
        def orgAId = TestPersonas.DENVER_METRO_BRANCH_ID

        and: "a project belonging to Org A"
        def project = dataBuilder.project()
                .forOrg(orgAId)
                .withTitle("Initial Title")
                .withDescription("Initial Description that is at least twenty chars long.")
                .withType(STANDARD)
                .withStatus(DRAFT)
                .persist()
        def projectId = project.id()

        and: "an update payload preserving Org A with updated title and description"
        def updateCmd = updateProjectCommand(orgAId, "Legit Updated Title", "Legit Updated Description that is at least twenty chars long.")

        when: "the Org Manager updates the project via HTTP PUT"
        def response = client.exchange(asOrgManager(HttpRequest.PUT("/projects/${projectId}", updateCmd)), Project)
        Project updated = response.body()

        then: "the update succeeds"
        verifyAll {
            response.status == HttpStatus.OK
            updated.id() == projectId
            updated.title() == "Legit Updated Title"
        }

        and: "the database reflects the updated fields while organization remains Org A"
        def projectInDb = sql.firstRow("SELECT organization_id, title, description FROM projects WHERE id = ?", [projectId])
        verifyAll(projectInDb) {
            organization_id == orgAId
            title == "Legit Updated Title"
        }
    }

    def "UPDATE | should allow project reassignment to another organization when user is GLOBAL_ADMIN"() {
        given: "two organizations Org A and Org B"
        def orgA = dataBuilder.organization().withName("Org A").asPublic().persist()
        def orgB = dataBuilder.organization().withName("Org B").asPublic().persist()
        def orgAId = orgA.id()
        def orgBId = orgB.id()

        and: "a project belonging to Org A"
        def project = dataBuilder.project()
                .forOrg(orgA)
                .withTitle("Original Title")
                .withDescription("Original Description that is at least twenty chars long.")
                .withType(STANDARD)
                .withStatus(DRAFT)
                .persist()
        def projectId = project.id()

        and: "an update command reassigning the project to Org B"
        def updateCmd = updateProjectCommand(orgBId, "Admin Updated Title", "Admin Updated Description that is at least twenty chars long.")

        when: "the Global Admin reassigns the project to Org B via HTTP PUT"
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/projects/${projectId}", updateCmd)), Project)
        Project updated = response.body()

        then: "the update succeeds"
        verifyAll {
            response.status == HttpStatus.OK
            updated.id() == projectId
            updated.title() == "Admin Updated Title"
        }

        and: "the project is reassigned to Org B in the database"
        def projectInDb = sql.firstRow("SELECT organization_id, title FROM projects WHERE id = ?", [projectId])
        verifyAll(projectInDb) {
            organization_id == orgBId
            title == "Admin Updated Title"
        }
    }

    def "UPDATE | should reject project reassignment even when user is manager of destination org"() {
        given: "two organizations Org A and Org B"
        def orgA = dataBuilder.organization().withName("Org A").asPublic().persist()
        def orgB = dataBuilder.organization().withName("Org B").asPublic().persist()
        def orgAId = orgA.id()
        def orgBId = orgB.id()

        and: "a user who is an ORG_MANAGER of both Org A and Org B"
        dataBuilder.organizationUser().asOrgManager(TestPersonas.LAURA_LOCAL_MANAGER, orgA).persist()
        dataBuilder.organizationUser().asOrgManager(TestPersonas.LAURA_LOCAL_MANAGER, orgB).persist()

        and: "a project belonging to Org A"
        def project = dataBuilder.project()
                .forOrg(orgA)
                .withTitle("Dual Mgr Project")
                .withDescription("Dual Mgr Description that is at least twenty chars long.")
                .withType(STANDARD)
                .withStatus(DRAFT)
                .persist()
        def projectId = project.id()

        and: "an update command reassigning the project to Org B"
        def updateCmd = updateProjectCommand(orgBId, "Reassigned by Dual Manager", "Desc with plenty of characters to pass validation.")

        when: "the dual manager attempts to reassign the project to Org B via HTTP PUT"
        client.exchange(asOrgManager(HttpRequest.PUT("/projects/${projectId}", updateCmd)), Project)

        then: "the update is rejected with 403 Forbidden"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN

        and: "the project remains assigned to Org A in the database"
        def projectInDb = sql.firstRow("SELECT organization_id FROM projects WHERE id = ?", [projectId])
        projectInDb.organization_id == orgAId
    }

    def "UPDATE | should throw 404 Not Found when privileged user reassigns project to non-existent organization"() {
        given: "an organization Org A"
        def orgA = dataBuilder.organization().withName("Org A").asPublic().persist()
        def orgAId = orgA.id()

        and: "a project belonging to Org A"
        def project = dataBuilder.project()
                .forOrg(orgA)
                .withTitle("Project to Reassign")
                .withDescription("Description of project to reassign at least 20 chars.")
                .withType(STANDARD)
                .withStatus(DRAFT)
                .persist()
        def projectId = project.id()

        and: "an update command with a non-existent destination organization"
        def nonExistentOrgId = UUID.randomUUID()
        def updateCmd = updateProjectCommand(nonExistentOrgId, "Ghost Org Project", "Description with plenty of characters to pass validation.")

        when: "the Global Admin reassigns the project to non-existent organization via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/projects/${projectId}", updateCmd)), Project)

        then: "a 404 Not Found exception is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND

        and: "the project in DB remains assigned to Org A"
        def projectInDb = sql.firstRow("SELECT organization_id FROM projects WHERE id = ?", [projectId])
        projectInDb.organization_id == orgAId
    }

    def "UPDATE | should demote ACTIVE project status to PENDING when location is updated outside boundary"() {
        given: "a region and an organization bounded to it"
        Coordinate[] denverCoords = [
                new Coordinate(-105.1099, 39.7891),
                new Coordinate(-104.7432, 39.7912),
                new Coordinate(-104.7528, 39.6158),
                new Coordinate(-105.0536, 39.6137),
                new Coordinate(-105.1099, 39.7891)
        ]
        def region = dataBuilder.region()
                .withName("City of Denver")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(denverCoords))
                .persist()
        def regionId = region.id()

        def org = dataBuilder.organization().withName("Denver Org").asPublic().verified().persist()
        def orgId = org.id()
        dataBuilder.linkOrganizationToRegion(org, region)

        and: "an Org Manager for the organization"
        dataBuilder.organizationUser().asOrgManager(TestPersonas.LAURA_LOCAL_MANAGER, org).persist()

        and: "an ACTIVE project belonging to the organization"
        def project = dataBuilder.project()
                .forOrg(org)
                .withTitle("Active Denver Project")
                .withDescription("Description of active Denver project at least 20 chars.")
                .withType(STANDARD)
                .withStatus(ACTIVE)
                .persist()
        def projectId = project.id()

        and: "an update command with a location outside the region (Boulder)"
        def outsideLoc = new ProjectLocationCommand(
                "Boulder Loc",
                "123 Pearl St",
                "Boulder",
                "CO",
                "80302",
                "US",
                -105.2705,
                40.0150
        )
        def updateCmd = updateProjectCommand(orgId, "Updated Active Project", "Updated Description with plenty of words to exceed twenty chars.", STANDARD, null, [outsideLoc])

        when: "the Org Manager updates the project with outside locations via HTTP PUT"
        def response = client.exchange(asOrgManager(HttpRequest.PUT("/projects/${projectId}", updateCmd)), Project)
        Project updated = response.body()

        then: "the project status is demoted to PENDING for regional review"
        verifyAll {
            response.status == HttpStatus.OK
            updated.status() == PENDING
        }

        and: "the status in database is PENDING"
        def projectInDb = sql.firstRow("SELECT status, title FROM projects WHERE id = ?", [projectId])
        verifyAll(projectInDb) {
            status == 'PENDING'
            title == "Updated Active Project"
        }
    }

    /********** DELETE Tests **********/

    def "DELETE | should remove an existing project"() {
        given: "a new project created via HTTP"
        def org = getRandomOrganization()
        def command = createProjectCommand(org.id(), "Temporary Project to Delete")
        Project saved = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", command)), Project).body()
        UUID id = saved.id()

        when: "the project is deleted via HTTP DELETE"
        def response = client.exchange(asGlobalAdmin(HttpRequest.DELETE("/projects/${id}")))

        then: "the project no longer exists in the repository or database"
        verifyAll {
            response.status == HttpStatus.OK || response.status == HttpStatus.NO_CONTENT
            !projectRepository.findById(id).isPresent()
            sql.firstRow("SELECT count(*) as count FROM projects WHERE id = ?", [id]).count == 0
        }
    }

    def "DELETE | should fail to delete a non-existent project"() {
        given: "a random non-existent ID"
        def nonExistentId = UUID.randomUUID()

        when: "a delete is attempted via HTTP DELETE"
        client.exchange(asGlobalAdmin(HttpRequest.DELETE("/projects/${nonExistentId}")))

        then: "a 404 Not Found exception is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    /********** SEARCH BY LOCATION Tests **********/

    def "SEARCH BY LOCATION | should successfully query projects by location point, returning closest locations first"() {
        given: "seed the database with test organization, projects, locations (closest to furthest), and active shifts"
        def org = dataBuilder.organization().withName("Distance Test Org").asPublic().persist()

        def projA = dataBuilder.project().forOrg(org).withTitle("Project A").withDescription("Description with at least twenty characters here.").withType(STANDARD).withStatus(ACTIVE).persist()
        def projB = dataBuilder.project().forOrg(org).withTitle("Project B").withDescription("Description with at least twenty characters here.").withType(STANDARD).withStatus(ACTIVE).persist()
        def projC = dataBuilder.project().forOrg(org).withTitle("Project C").withDescription("Description with at least twenty characters here.").withType(STANDARD).withStatus(ACTIVE).persist()
        def projD = dataBuilder.project().forOrg(org).withTitle("Project D").withDescription("Description with at least twenty characters here.").withType(STANDARD).withStatus(ACTIVE).persist()
        def projE = dataBuilder.project().forOrg(org).withTitle("Project E").withDescription("Description with at least twenty characters here.").withType(STANDARD).withStatus(ACTIVE).persist()

        def locA1 = dataBuilder.location().withName('Location A1').withAddressLine('123 Closest St').withCity('Denver').withCountryCode('US').atPoint(-104.9903, 39.7572).persist()
        def locB1 = dataBuilder.location().withName('Location B1').withAddressLine('456 Second St').withCity('Denver').withCountryCode('US').atPoint(-104.9903, 39.7842).persist()
        def locA2 = dataBuilder.location().withName('Location A2').withAddressLine('789 Third St').withCity('Denver').withCountryCode('US').atPoint(-104.9903, 39.8292).persist()
        def locE1 = dataBuilder.location().withName('Location E1').withAddressLine('101 Fourth St').withCity('Denver').withCountryCode('US').atPoint(-104.9903, 39.8472).persist()
        def locC1 = dataBuilder.location().withName('Location C1').withAddressLine('202 Fifth St').withCity('Denver').withCountryCode('US').atPoint(-104.9903, 39.8742).persist()
        def locA3 = dataBuilder.location().withName('Location A3').withAddressLine('303 Far St').withCity('Denver').withCountryCode('US').atPoint(-104.9903, 40.1892).persist()
        def locD1 = dataBuilder.location().withName('Location D1').withAddressLine('404 Far St').withCity('Denver').withCountryCode('US').atPoint(-104.9903, 40.1892).persist()

        dataBuilder.addProjectLocation(projA, locA1)
        dataBuilder.addProjectLocation(projA, locA2)
        dataBuilder.addProjectLocation(projA, locA3)
        dataBuilder.addProjectLocation(projB, locB1)
        dataBuilder.addProjectLocation(projE, locE1)
        dataBuilder.addProjectLocation(projC, locC1)
        dataBuilder.addProjectLocation(projD, locD1)

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC)
        dataBuilder.shift().forProject(projA).atLocation(locA1).withTimes(now.plusDays(1), now.plusDays(1).plusHours(2)).persist()
        dataBuilder.shift().forProject(projA).atLocation(locA2).withTimes(now.plusDays(1), now.plusDays(1).plusHours(2)).persist()
        dataBuilder.shift().forProject(projA).atLocation(locA3).withTimes(now.plusDays(1), now.plusDays(1).plusHours(2)).persist()
        dataBuilder.shift().forProject(projB).atLocation(locB1).withTimes(now.plusDays(1), now.plusDays(1).plusHours(2)).persist()
        dataBuilder.shift().forProject(projE).atLocation(locE1).withTimes(now.plusDays(1), now.plusDays(1).plusHours(2)).persist()
        dataBuilder.shift().forProject(projC).atLocation(locC1).withTimes(now.plusDays(1), now.plusDays(1).plusHours(2)).persist()
        dataBuilder.shift().forProject(projD).atLocation(locD1).withTimes(now.plusDays(1), now.plusDays(1).plusHours(2)).persist()

        when: "searching projects by location via HTTP GET with 20km radius"
        def uri = UriBuilder.of("/projects/search-by-location")
                .queryParam("longitude", -104.9903)
                .queryParam("latitude", 39.7392)
                .queryParam("radiusMeters", 20000.0)
                .queryParam("size", 100)
                .build().toString()
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET(uri)), Map)

        then: "only active projects in range are returned sorted by closest location, with no duplicates per project"
        List items = response.body().content as List
        List testResults = items.findAll { (it.projectId as String) in [projA.id(), projB.id(), projC.id(), projD.id(), projE.id()].collect { it.toString() } }
        verifyAll {
            response.status == HttpStatus.OK
            testResults.size() == 4
            testResults[0].projectId == projA.id().toString()
            testResults[1].projectId == projB.id().toString()
            testResults[2].projectId == projE.id().toString()
            testResults[3].projectId == projC.id().toString()
        }
    }

    @Unroll
    def "SEARCH BY LOCATION | should handle project status #status and shift active state #shiftActive"() {
        given: "seed the database with test organization, project statuses, and shift time configurations"
        def org = dataBuilder.organization().withName("Status Test Org").asPublic().persist()

        def projA = dataBuilder.project()
                .forOrg(org)
                .withTitle("Project A")
                .withDescription("Description with at least twenty characters here.")
                .withType(STANDARD)
                .withStatus(ProjectStatus.valueOf(status))
                .persist()

        def projB = dataBuilder.project()
                .forOrg(org)
                .withTitle("Project B")
                .withDescription("Description with at least twenty characters here.")
                .withType(STANDARD)
                .withStatus(ACTIVE)
                .persist()

        def locA1 = dataBuilder.location()
                .withName('Location A1')
                .withAddressLine('123 Closest St')
                .withCity('Denver')
                .withCountryCode('US')
                .atPoint(-104.9903, 39.7572)
                .persist()

        def locB1 = dataBuilder.location()
                .withName('Location B1')
                .withAddressLine('456 Second St')
                .withCity('Denver')
                .withCountryCode('US')
                .atPoint(-104.9903, 39.7842)
                .persist()

        def locA2 = dataBuilder.location()
                .withName('Location A2')
                .withAddressLine('789 Third St')
                .withCity('Denver')
                .withCountryCode('US')
                .atPoint(-104.9903, 39.8292)
                .persist()

        dataBuilder.addProjectLocation(projA, locA1)
        dataBuilder.addProjectLocation(projA, locA2)
        dataBuilder.addProjectLocation(projB, locB1)

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC)
        OffsetDateTime shiftA1Start = shiftActive ? now.plusDays(1) : now.minusDays(1)
        OffsetDateTime shiftA1End = shiftActive ? now.plusDays(1).plusHours(2) : now.minusHours(22)

        dataBuilder.shift()
                .forProject(projA)
                .atLocation(locA1)
                .withTimes(shiftA1Start, shiftA1End)
                .persist()

        dataBuilder.shift()
                .forProject(projA)
                .atLocation(locA2)
                .withTimes(now.plusDays(1), now.plusDays(1).plusHours(2))
                .persist()

        dataBuilder.shift()
                .forProject(projB)
                .atLocation(locB1)
                .withTimes(now.plusDays(1), now.plusDays(1).plusHours(2))
                .persist()

        when: "searching projects by location via HTTP GET"
        def uri = UriBuilder.of("/projects/search-by-location")
                .queryParam("longitude", -104.9903)
                .queryParam("latitude", 39.7392)
                .queryParam("radiusMeters", 20000.0)
                .queryParam("size", 100)
                .build().toString()
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET(uri)), Map)

        then: "the result matches expected order and presence based on project status and shift validity"
        List items = response.body().content as List
        List testResults = items.findAll { (it.projectId as String) in [projA.id(), projB.id()].collect { it.toString() } }

        List<String> expectedUUIDs = expectedOrderNames.collect { name ->
            (name == 'A' ? projA.id() : projB.id()).toString()
        }
        verifyAll {
            response.status == HttpStatus.OK
            testResults.collect { it.projectId as String } == expectedUUIDs
        }


        where:
        status     | shiftActive | expectedOrderNames
        'ACTIVE'   | true        | ['A', 'B']
        'ACTIVE'   | false       | ['B', 'A']
        'DRAFT'    | true        | ['B']
        'PENDING'  | true        | ['B']
        'FLAGGED'  | true        | ['B']
        'REJECTED' | true        | ['B']
    }

    @Unroll
    def "SEARCH | should reject invalid coordinates: lon=#lon, lat=#lat, rad=#rad"(double lon, double lat, double rad) {
        when: "searching projects by location with invalid coordinates or radius via HTTP GET"
        def uri = UriBuilder.of("/projects/search-by-location")
                .queryParam("longitude", lon)
                .queryParam("latitude", lat)
                .queryParam("radiusMeters", rad)
                .build().toString()
        client.exchange(asGlobalAdmin(HttpRequest.GET(uri)), Map)

        then: "a 400 Bad Request exception is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST

        where:
        lon    | lat   | rad
        -190.0 | 0.0   | 1000.0
        195.0  | 0.0   | 1000.0
        0.0    | -95.0 | 1000.0
        0.0    | 95.0  | 1000.0
        0.0    | 0.0   | -1.0
        0.0    | 0.0   | 600000.0
    }

    def "SEARCH BY LOCATION | should return empty page when no projects exist within search radius"() {
        when: "searching in the middle of the Atlantic Ocean where zero projects exist via HTTP GET"
        def uri = UriBuilder.of("/projects/search-by-location")
                .queryParam("longitude", 0.0)
                .queryParam("latitude", 0.0)
                .queryParam("radiusMeters", 1000.0)
                .build().toString()
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET(uri)), Map)

        then: "an empty Page is returned"
        def content = response.body().content as List
        verifyAll {
            response.status == HttpStatus.OK
            content.isEmpty()
        }
    }

    /********** APPROVE Tests **********/

    def "APPROVE | should allow GLOBAL_ADMIN to approve a virtual project"() {
        given: "an organization and a pending virtual project"
        def org = dataBuilder.organization().withName("Approve Org").asPublic().persist()
        def project = dataBuilder.project()
                .forOrg(org)
                .withTitle("Virtual Approve Project")
                .withDescription("Description with at least twenty characters here.")
                .withType(STANDARD)
                .withStatus(PENDING)
                .persist()
        def projId = project.id()

        when: "the GLOBAL_ADMIN approves the project via HTTP PUT"
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/projects/${projId}/status", "")), Project)
        Project approved = response.body()

        then: "the project transitions to ACTIVE"
        verifyAll {
            response.status == HttpStatus.OK
            approved.id() == projId
            approved.status() == ACTIVE
        }

        and: "the status is persisted in the database"
        def inDb = sql.firstRow("SELECT status FROM projects WHERE id = ?", [projId])
        inDb.status == 'ACTIVE'
    }

    def "APPROVE | should allow REGION_DIRECTOR to approve a virtual project when org is in their region"() {
        given: "a region, organization in that region, and pending virtual project"
        Coordinate[] regionCoords = [
                new Coordinate(-105.1, 39.7),
                new Coordinate(-104.7, 39.7),
                new Coordinate(-104.7, 39.6),
                new Coordinate(-105.1, 39.6),
                new Coordinate(-105.1, 39.7)
        ]
        def region = dataBuilder.region()
                .withName("Approve Region")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(regionCoords))
                .persist()
        def regId = region.id()
        def org = dataBuilder.organization().withName("Director Org").asPublic().verified().persist()
        dataBuilder.linkOrganizationToRegion(org, region)

        def project = dataBuilder.project()
                .forOrg(org)
                .withTitle("Director Virtual Project")
                .withDescription("Description with at least twenty characters here.")
                .withType(STANDARD)
                .withStatus(PENDING)
                .persist()
        def projId = project.id()

        and: "a REGION_DIRECTOR of that region"
        dataBuilder.regionUser().asRegionDirector(TestPersonas.DANIEL_COUNTY_DIRECTOR, region).persist()

        when: "the REGION_DIRECTOR approves the virtual project via HTTP PUT"
        def response = client.exchange(asRegionDirector(HttpRequest.PUT("/projects/${projId}/status", ""), TestPersonas.DANIEL_COUNTY_DIRECTOR), Project)
        Project approved = response.body()

        then: "the project transitions to ACTIVE"
        verifyAll {
            response.status == HttpStatus.OK
            approved.id() == projId
            approved.status() == ACTIVE
        }

        cleanup:
        executeUpdate("DELETE FROM region_users WHERE user_id = ? AND region_id = ?", TestPersonas.DANIEL_COUNTY_DIRECTOR.id, regId)
    }

    def "APPROVE | should reject REGION_AGENT approval of virtual project with 403 Forbidden"() {
        given: "an organization and pending virtual project"
        def org = dataBuilder.organization().withName("Agent VOrg").asPublic().persist()
        def project = dataBuilder.project()
                .forOrg(org)
                .withTitle("Agent VProj")
                .withDescription("Description with at least twenty characters here.")
                .withType(STANDARD)
                .withStatus(PENDING)
                .persist()
        def projId = project.id()

        when: "the REGION_AGENT attempts to approve the virtual project via HTTP PUT"
        client.exchange(asRegionAgent(HttpRequest.PUT("/projects/${projId}/status", ""), TestPersonas.CONNIE_COUNTY_AGENT), Project)

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "APPROVE | should reject approval of non-existent project with 404 Not Found"() {
        when: "approving a non-existent project via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/projects/${UUID.randomUUID()}/status", "")), Project)

        then: "a 404 Not Found is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "APPROVE | should reject approval of already ACTIVE project with 400 Bad Request"() {
        given: "an already ACTIVE project"
        def org = dataBuilder.organization().withName("Active Org").asPublic().persist()
        def project = dataBuilder.project()
                .forOrg(org)
                .withTitle("Already Active")
                .withDescription("Description with at least twenty characters here.")
                .withType(STANDARD)
                .withStatus(ACTIVE)
                .persist()
        def projId = project.id()

        when: "attempting to approve an ACTIVE project via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/projects/${projId}/status", "")), Project)

        then: "a 400 Bad Request is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
    }

    def "APPROVE | should allow approving a PENDING_UPDATE project"() {
        given: "a project in PENDING_UPDATE status"
        def org = dataBuilder.organization().withName("PendingUpdate Org").asPublic().persist()
        def project = dataBuilder.project()
                .forOrg(org)
                .withTitle("Pending Update Proj")
                .withDescription("Description with at least twenty characters here.")
                .withType(STANDARD)
                .withStatus(PENDING_UPDATE)
                .persist()
        def projId = project.id()

        when: "approving the PENDING_UPDATE project via HTTP PUT"
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/projects/${projId}/status", "")), Project)
        Project approved = response.body()

        then: "the project transitions to ACTIVE"
        verifyAll {
            response.status == HttpStatus.OK
            approved.status() == ACTIVE
        }
    }

    def "APPROVE | should reject approval of soft-deleted project with 404 Not Found"() {
        given: "a soft-deleted pending project"
        def org = dataBuilder.organization().withName("Deleted Org").asPublic().persist()
        def project = dataBuilder.project()
                .forOrg(org)
                .withTitle("Deleted Proj")
                .withDescription("Description with at least twenty characters here.")
                .withType(STANDARD)
                .withStatus(PENDING)
                .deleted()
                .persist()
        def projId = project.id()

        when: "attempting to approve a deleted project via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/projects/${projId}/status", "")), Project)

        then: "a 404 Not Found is thrown preventing resurrection"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "APPROVE | should write audit log record on approval"() {
        given: "a pending project and an admin"
        def org = dataBuilder.organization().withName("Audit Org").asPublic().persist()
        def project = dataBuilder.project()
                .forOrg(org)
                .withTitle("Audit Proj")
                .withDescription("Description with at least twenty characters here.")
                .withType(STANDARD)
                .withStatus(PENDING)
                .persist()
        def projId = project.id()

        when: "approving the project via HTTP PUT"
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/projects/${projId}/status", "")), Project)

        then: "a project_audit_logs record is written"
        def auditRow = sql.firstRow("SELECT action, actor_id FROM project_audit_logs WHERE project_id = ?", [projId])
        verifyAll {
            response.status == HttpStatus.OK
            auditRow != null
            auditRow.action == 'APPROVED'
            auditRow.actor_id == UUID.fromString(adminId)
        }
    }

    def "APPROVE | should allow REGION_DIRECTOR to approve physical project with locations inside their region"() {
        given: "a region, organization, location inside that region, and physical project"
        Coordinate[] regionCoords = [
                new Coordinate(-105.1, 39.8),
                new Coordinate(-104.7, 39.8),
                new Coordinate(-104.7, 39.6),
                new Coordinate(-105.1, 39.6),
                new Coordinate(-105.1, 39.8)
        ]
        def region = dataBuilder.region()
                .withName("Director Phys Region")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(regionCoords))
                .persist()
        def regId = region.id()
        def org = dataBuilder.organization().withName("Phys Org").asPublic().persist()
        def loc = dataBuilder.location()
                .withName("Phys Loc")
                .withAddressLine("St")
                .withCity("Denver")
                .withCountryCode("US")
                .atPoint(-104.9903, 39.7392)
                .persist()

        def project = dataBuilder.project()
                .forOrg(org)
                .withTitle("Phys Project")
                .withDescription("Description with at least twenty characters here.")
                .withType(STANDARD)
                .withStatus(PENDING)
                .atLocation(loc)
                .persist()
        def projId = project.id()

        and: "a REGION_DIRECTOR assigned to that region"
        dataBuilder.regionUser().asRegionDirector(TestPersonas.DANIEL_COUNTY_DIRECTOR, region).persist()

        when: "the REGION_DIRECTOR approves the physical project via HTTP PUT"
        def response = client.exchange(asRegionDirector(HttpRequest.PUT("/projects/${projId}/status", ""), TestPersonas.DANIEL_COUNTY_DIRECTOR), Project)
        Project approved = response.body()

        then: "the physical project transitions to ACTIVE"
        verifyAll {
            response.status == HttpStatus.OK
            approved.id() == projId
            approved.status() == ACTIVE
        }

        cleanup:
        executeUpdate("DELETE FROM region_users WHERE user_id = ? AND region_id = ?", TestPersonas.DANIEL_COUNTY_DIRECTOR.id, regId)
    }

    def "APPROVE | should allow REGION_DIRECTOR to approve virtual project assigned via managing_region_id"() {
        given: "a region, verified organization, and virtual project explicitly assigned via managing_region_id"
        Coordinate[] regionCoords = [
                new Coordinate(-105.1, 39.8),
                new Coordinate(-104.7, 39.8),
                new Coordinate(-104.7, 39.6),
                new Coordinate(-105.1, 39.6),
                new Coordinate(-105.1, 39.8)
        ]
        def region = dataBuilder.region()
                .withName("Director Managing Reg")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(regionCoords))
                .persist()
        def regId = region.id()
        def org = dataBuilder.organization().withName("Managing Reg Org").asPublic().verified().persist()

        def project = dataBuilder.project()
                .forOrg(org)
                .inRegion(region)
                .withTitle("Virtual Assigned Project")
                .withDescription("Description with at least twenty characters here.")
                .withType(STANDARD)
                .withStatus(PENDING)
                .persist()
        def projId = project.id()

        and: "a REGION_DIRECTOR assigned to that managing region"
        dataBuilder.regionUser().asRegionDirector(TestPersonas.DANIEL_COUNTY_DIRECTOR, region).persist()

        when: "the REGION_DIRECTOR approves the virtual project via HTTP PUT"
        def response = client.exchange(asRegionDirector(HttpRequest.PUT("/projects/${projId}/status", ""), TestPersonas.DANIEL_COUNTY_DIRECTOR), Project)
        Project approved = response.body()

        then: "the project transitions to ACTIVE"
        verifyAll {
            response.status == HttpStatus.OK
            approved.id() == projId
            approved.status() == ACTIVE
        }

        cleanup:
        executeUpdate("DELETE FROM region_users WHERE user_id = ? AND region_id = ?", TestPersonas.DANIEL_COUNTY_DIRECTOR.id, regId)
    }

    def "APPROVE | should reject approval of DRAFT project with 400 Bad Request"() {
        given: "a project in DRAFT status"
        def org = getRandomOrganization()
        def command = createProjectCommand(org.id(), "Draft Approval Project", "Description with at least twenty characters here.", STANDARD, DRAFT)
        Project saved = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", command)), Project).body()

        when: "attempting to approve the draft project via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/projects/${saved.id()}/status", "")), Project)

        then: "a 400 Bad Request is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
    }

    def "APPROVE | should reject approval when REGION_DIRECTOR lacks geographic jurisdiction over physical locations"() {
        given: "a PENDING project with physical locations in Colorado Springs (outside Denver County)"
        def org = getRandomOrganization()
        def csLoc = dataBuilder.location()
                .withName("CS Loc")
                .withAddressLine("123 Main")
                .withCity("Colo Springs")
                .withCountryCode("US")
                .atPoint(-104.82, 38.83)
                .persist()
        def project = dataBuilder.project()
                .forOrg(org)
                .withTitle("CS Project")
                .withDescription("Description with at least twenty characters here.")
                .withType(STANDARD)
                .withStatus(PENDING)
                .atLocation(csLoc)
                .persist()
        def projId = project.id()

        when: "the Denver Region Director attempts to approve the project via HTTP PUT"
        client.exchange(asRegionDirector(HttpRequest.PUT("/projects/${projId}/status", ""), TestPersonas.DANIEL_COUNTY_DIRECTOR), Project)

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "APPROVE | should throw 404 Not Found when approving principal does not exist in users table"() {
        given: "a PENDING project"
        def org = getRandomOrganization()
        def command = createProjectCommand(org.id(), "Actor 404 Project", "Description with at least twenty characters here.", STANDARD, PENDING)
        Project saved = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", command)), Project).body()

        when: "calling approveProject directly with a principal UUID that is not in the users table"
        def nonExistentUserId = UUID.randomUUID()
        projectController.approveProject(saved.id(), createPrincipal(nonExistentUserId))

        then: "a 404 Not Found is thrown"
        def e = thrown(HttpStatusException)
        verifyAll(e) {
            status == HttpStatus.NOT_FOUND
            message == "User not found"
        }
    }

    /********** MANAGING REGION Tests **********/

    def "SUBMIT | should throw 400 when managingRegion does not exist"() {
        given: "a create project command referencing a non-existent managing region"
        def org = getRandomOrganization()
        def nonExistentRegionId = UUID.randomUUID()
        def command = createProjectCommand(org.id(), "Ghost Project", "Description with at least twenty characters here.", STANDARD, DRAFT, nonExistentRegionId)

        when: "submitting the project via HTTP POST"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", command)), Project)

        then: "a 400 Bad Request is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
    }

    def "SUBMIT | should throw 400 when project locations do not fall within managingRegion"() {
        given: "a managing region with a defined boundary"
        Coordinate[] regionCoords = [
                new Coordinate(-105.1, 39.8),
                new Coordinate(-104.7, 39.8),
                new Coordinate(-104.7, 39.6),
                new Coordinate(-105.1, 39.6),
                new Coordinate(-105.1, 39.8)
        ]
        def region = dataBuilder.region()
                .withName("Strict Region")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(regionCoords))
                .persist()
        def regId = region.id()

        and: "an organization and a location outside the managing region"
        def org = getRandomOrganization()
        def outLoc = new ProjectLocationCommand(
                "Out Loc",
                "123 St",
                "Boulder",
                null,
                null,
                "US",
                -106.0,
                41.0
        )
        def command = createProjectCommand(org.id(), "Out Project", "Description with at least twenty characters here.", STANDARD, DRAFT, regId, [outLoc])

        when: "submitting the project via HTTP POST"
        client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", command)), Project)

        then: "a 400 Bad Request is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
    }

    def "SUBMIT | should throw 403 when submitter lacks authority to assign managingRegion"() {
        given: "a managing region"
        Coordinate[] regionCoords = [
                new Coordinate(-105.1, 39.8),
                new Coordinate(-104.7, 39.8),
                new Coordinate(-104.7, 39.6),
                new Coordinate(-105.1, 39.6),
                new Coordinate(-105.1, 39.8)
        ]
        def region = dataBuilder.region()
                .withName("Auth Region")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(regionCoords))
                .persist()
        def regId = region.id()

        and: "an organization"
        def org = getRandomOrganization()

        def command = createProjectCommand(org.id(), "Unauthorized Region Project", "Description with at least twenty characters here.", STANDARD, DRAFT, regId)

        when: "submitting the project via HTTP POST by unauthorized user"
        client.exchange(asVolunteer(HttpRequest.POST("/projects", command)), Project)

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "SUBMIT | should successfully save virtual project with valid managingRegion"() {
        given: "a valid region and organization where user has authority"
        Coordinate[] regionCoords = [
                new Coordinate(-105.1, 39.7),
                new Coordinate(-104.9, 39.7),
                new Coordinate(-104.9, 39.8),
                new Coordinate(-105.1, 39.8),
                new Coordinate(-105.1, 39.7)
        ]
        def region = dataBuilder.region()
                .withName("Submit Virtual Region")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(regionCoords))
                .persist()
        def regId = region.id()
        def org = getRandomOrganization()
        def command = createProjectCommand(org.id(), "Virtual Region Proj", "Description with at least twenty characters here.", STANDARD, DRAFT, regId)

        when: "submitting virtual project with global admin via HTTP POST"
        def response = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", command)), Project)
        Project saved = response.body()

        then: "project is saved and managingRegion is set"
        verifyAll {
            response.status == HttpStatus.OK
            saved.id() != null
            saved.managingRegion() != null
            saved.managingRegion().id() == regId
        }
    }

    def "UPDATE | should throw 400 when updating project locations outside existing managingRegion"() {
        given: "a managing region and an active project inside it"
        Coordinate[] regionCoords = [
                new Coordinate(-105.1, 39.8),
                new Coordinate(-104.7, 39.8),
                new Coordinate(-104.7, 39.6),
                new Coordinate(-105.1, 39.6),
                new Coordinate(-105.1, 39.8)
        ]
        def region = dataBuilder.region()
                .withName("Update Region")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(regionCoords))
                .persist()
        def regId = region.id()
        def org = getRandomOrganization()

        def inLoc = new ProjectLocationCommand("In Loc", "123 St", "Denver", null, null, "US", -104.9, 39.7)
        def createCmd = createProjectCommand(org.id(), "Initial Project", "Description with at least twenty characters here.", STANDARD, DRAFT, regId, [inLoc])
        Project saved = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", createCmd)), Project).body()

        when: "updating the project with locations outside the managing region without modifying managingRegion"
        def outLoc = new ProjectLocationCommand("Out Loc", "123 St", "Boulder", null, null, "US", -106.0, 41.0)
        def updateCmd = updateProjectCommand(org.id(), "Initial Project", "Description with at least twenty characters here.", STANDARD, regId, [outLoc])
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/projects/${saved.id()}", updateCmd)), Project)

        then: "a 400 Bad Request is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
    }

    def "UPDATE | should throw 403 when user lacks authority to assign new managingRegion on update"() {
        given: "an organization with an Org Manager and a separate foreign region"
        def org = dataBuilder.organization().withName("Update Region Org").asPublic().verified().persist()
        def orgId = org.id()
        dataBuilder.organizationUser().asOrgManager(TestPersonas.LAURA_LOCAL_MANAGER, org).persist()

        Coordinate[] foreignCoords = [
                new Coordinate(-105.1, 39.7),
                new Coordinate(-104.9, 39.7),
                new Coordinate(-104.9, 39.8),
                new Coordinate(-105.1, 39.8),
                new Coordinate(-105.1, 39.7)
        ]
        def foreignRegion = dataBuilder.region()
                .withName("Foreign Region")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(foreignCoords))
                .persist()
        def foreignRegionId = foreignRegion.id()

        def createCmd = createProjectCommand(orgId, "Unauth Region Proj", "Description with at least twenty characters here.", STANDARD, DRAFT)
        Project saved = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", createCmd)), Project).body()

        when: "the Org Manager attempts to assign a foreign region not linked to their organization via HTTP PUT"
        def updateCmd = updateProjectCommand(orgId, "Unauth Region Proj", "Description with at least twenty characters here.", STANDARD, foreignRegionId)
        client.exchange(asOrgManager(HttpRequest.PUT("/projects/${saved.id()}", updateCmd)), Project)

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "UPDATE | should throw 403 when unauthorized user attempts to unassign managingRegion"() {
        given: "a project with a managingRegion in Region B under an organization not bound to Region B"
        Coordinate[] regBCoords = [
                new Coordinate(-104.8, 39.7),
                new Coordinate(-104.6, 39.7),
                new Coordinate(-104.6, 39.8),
                new Coordinate(-104.8, 39.8),
                new Coordinate(-104.8, 39.7)
        ]
        def regionB = dataBuilder.region()
                .withName("Region B")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(regBCoords))
                .persist()
        def regBId = regionB.id()

        def org = dataBuilder.organization().withName("Org Unassign Region").asPublic().verified().persist()
        def orgId = org.id()
        dataBuilder.organizationUser().asOrgManager(TestPersonas.LAURA_LOCAL_MANAGER, org).persist()

        def createCmd = createProjectCommand(orgId, "Project with Region B", "Description with at least twenty characters here.", STANDARD, DRAFT, regBId)
        Project saved = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", createCmd)), Project).body()

        when: "the Org Manager attempts to unassign the managingRegion via HTTP PUT"
        def updateCmd = updateProjectCommand(orgId, "Project with Region B", "Description with at least twenty characters here.", STANDARD, null)
        client.exchange(asOrgManager(HttpRequest.PUT("/projects/${saved.id()}", updateCmd)), Project)

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "UPDATE | should allow authorized Org Manager to unassign managingRegion"() {
        given: "a region and an organization bounded to it with an Org Manager"
        Coordinate[] regionCoords = [
                new Coordinate(-105.1, 39.7),
                new Coordinate(-104.9, 39.7),
                new Coordinate(-104.9, 39.8),
                new Coordinate(-105.1, 39.8),
                new Coordinate(-105.1, 39.7)
        ]
        def region = dataBuilder.region()
                .withName("Region For Unassign")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(regionCoords))
                .persist()
        def regId = region.id()

        def org = dataBuilder.organization().withName("Org For Unassign").asPublic().verified().persist()
        def orgId = org.id()
        dataBuilder.linkOrganizationToRegion(org, region)
        dataBuilder.organizationUser().asOrgManager(TestPersonas.LAURA_LOCAL_MANAGER, org).persist()

        def createCmd = createProjectCommand(orgId, "Project with Region", "Description with at least twenty characters here.", STANDARD, DRAFT, regId)
        Project saved = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", createCmd)), Project).body()

        when: "the Org Manager updates the project setting managingRegion to null via HTTP PUT"
        def updateCmd = updateProjectCommand(orgId, "Project with Region", "Description with at least twenty characters here.", STANDARD, null)
        def response = client.exchange(asOrgManager(HttpRequest.PUT("/projects/${saved.id()}", updateCmd)), Project)
        Project updated = response.body()

        then: "managingRegion is successfully unassigned"
        def dbRow = sql.firstRow("SELECT managing_region_id FROM projects WHERE id = ?", [saved.id()])
        verifyAll {
            response.status == HttpStatus.OK
            updated.managingRegion() == null
            dbRow.managing_region_id == null
        }
    }

    def "UPDATE | should demote ACTIVE project to PENDING when reassigned to organization outside geographic bounds"() {
        given: "two regions: Denver and Boulder"
        Coordinate[] denverCoords = [
                new Coordinate(-105.1099, 39.7891),
                new Coordinate(-104.7432, 39.7912),
                new Coordinate(-104.7528, 39.6158),
                new Coordinate(-105.0536, 39.6137),
                new Coordinate(-105.1099, 39.7891)
        ]
        def denverReg = dataBuilder.region()
                .withName("Denver City Reassign")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(denverCoords))
                .persist()
        def denverRegId = denverReg.id()

        Coordinate[] boulderCoords = [
                new Coordinate(-105.35, 39.95),
                new Coordinate(-105.15, 39.95),
                new Coordinate(-105.15, 40.10),
                new Coordinate(-105.35, 40.10),
                new Coordinate(-105.35, 39.95)
        ]
        def boulderReg = dataBuilder.region()
                .withName("Boulder City Reassign")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(boulderCoords))
                .persist()
        def boulderRegId = boulderReg.id()

        def orgA = dataBuilder.organization().withName("Denver Reassign Org A").asPublic().verified().persist()
        def orgAId = orgA.id()
        dataBuilder.linkOrganizationToRegion(orgA, denverReg)

        def orgB = dataBuilder.organization().withName("Boulder Reassign Org B").asPublic().verified().persist()
        def orgBId = orgB.id()
        dataBuilder.linkOrganizationToRegion(orgB, boulderReg)

        and: "an ACTIVE project under Denver Org with a location in Denver"
        def denverLoc = new ProjectLocationCommand("Denver Reassign Loc", "16th St", "Denver", "CO", "80202", "US", -104.9, 39.7)
        def createCmd = createProjectCommand(orgAId, "Active Denver Reassign Project", "Description with at least twenty characters here.", STANDARD, ACTIVE, null, [denverLoc])
        Project saved = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", createCmd)), Project).body()
        executeUpdate("UPDATE projects SET status = 'ACTIVE' WHERE id = ?", saved.id())

        when: "a Global Admin reassigns the active Denver project to Boulder Org via HTTP PUT"
        def updateCmd = updateProjectCommand(orgBId, "Active Denver Reassign Project", "Description with at least twenty characters here.", STANDARD, null, [denverLoc])
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/projects/${saved.id()}", updateCmd)), Project)
        Project updated = response.body()

        then: "the status is automatically demoted to PENDING for regional review"
        def row = sql.firstRow("SELECT status, organization_id FROM projects WHERE id = ?", [saved.id()])
        verifyAll {
            response.status == HttpStatus.OK
            updated.status() == PENDING
            row.status == 'PENDING'
            row.organization_id == orgBId
        }
    }

    def "UPDATE | should preserve ACTIVE status when reassigned to organization containing geographic bounds"() {
        given: "a shared regional boundary enclosing Denver"
        Coordinate[] denverCoords = [
                new Coordinate(-105.1099, 39.7891),
                new Coordinate(-104.7432, 39.7912),
                new Coordinate(-104.7528, 39.6158),
                new Coordinate(-105.0536, 39.6137),
                new Coordinate(-105.1099, 39.7891)
        ]
        def region = dataBuilder.region()
                .withName("Shared Denver Region")
                .withGeometry(dataBuilder.geometryFactory.createPolygon(denverCoords))
                .persist()

        def org1 = dataBuilder.organization().withName("Org Alpha Shared").asPublic().verified().persist()
        def org1Id = org1.id()
        dataBuilder.linkOrganizationToRegion(org1, region)

        def org2 = dataBuilder.organization().withName("Org Beta Shared").asPublic().verified().persist()
        def org2Id = org2.id()
        dataBuilder.linkOrganizationToRegion(org2, region)

        and: "an ACTIVE project in Org Alpha"
        def denverLoc = new ProjectLocationCommand("Denver Shared Loc", "16th St", "Denver", "CO", "80202", "US", -104.9, 39.7)
        def createCmd = createProjectCommand(org1Id, "Active Alpha Shared Proj", "Description with at least twenty characters here.", STANDARD, ACTIVE, null, [denverLoc])
        Project saved = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", createCmd)), Project).body()
        executeUpdate("UPDATE projects SET status = 'ACTIVE' WHERE id = ?", saved.id())

        when: "reassigning to Org Beta which also encloses the location via HTTP PUT"
        def updateCmd = updateProjectCommand(org2Id, "Active Alpha Shared Proj", "Description with at least twenty characters here.", STANDARD, null, [denverLoc])
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/projects/${saved.id()}", updateCmd)), Project)
        Project updated = response.body()

        then: "status is preserved as ACTIVE"
        def row = sql.firstRow("SELECT status, organization_id FROM projects WHERE id = ?", [saved.id()])
        verifyAll {
            response.status == HttpStatus.OK
            updated.status() == ACTIVE
            row.status == 'ACTIVE'
            row.organization_id == org2Id
        }
    }

    def "UPDATE | should reject project update by unaffiliated standard user with 403 Forbidden"() {
        given: "an existing project and an unaffiliated standard user"
        def org = getRandomOrganization()
        def createCmd = createProjectCommand(org.id(), "Protected Project", "Description with at least twenty characters here.")
        Project saved = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", createCmd)), Project).body()

        when: "unaffiliated user attempts to update the project via HTTP PUT"
        def updateCmd = updateProjectCommand(org.id(), "Tampered Title", "Description with at least twenty characters here.")
        client.exchange(asVolunteer(HttpRequest.PUT("/projects/${saved.id()}", updateCmd)), Project)

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "DELETE | should reject project deletion by unaffiliated user with 403 Forbidden"() {
        given: "an existing project and an unaffiliated standard user"
        def org = getRandomOrganization()
        def createCmd = createProjectCommand(org.id(), "Protected Delete Proj", "Description with at least twenty characters here.")
        Project saved = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", createCmd)), Project).body()

        when: "unaffiliated user attempts to delete the project via HTTP DELETE"
        client.exchange(asVolunteer(HttpRequest.DELETE("/projects/${saved.id()}")))

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "DELETE | should allow Org Manager to delete project belonging to their organization"() {
        given: "an organization with an Org Manager and a project"
        def org = dataBuilder.organization().withName("Delete Test Org").asPublic().verified().persist()
        def orgId = org.id()
        dataBuilder.organizationUser().asOrgManager(TestPersonas.LAURA_LOCAL_MANAGER, org).persist()

        def createCmd = createProjectCommand(orgId, "Project to Delete", "Description with at least twenty characters here.")
        Project saved = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", createCmd)), Project).body()

        when: "the Org Manager deletes the project via HTTP DELETE"
        def response = client.exchange(asOrgManager(HttpRequest.DELETE("/projects/${saved.id()}")))

        then: "the project is deleted from the database"
        verifyAll {
            response.status == HttpStatus.OK || response.status == HttpStatus.NO_CONTENT
            sql.firstRow("SELECT id FROM projects WHERE id = ?", [saved.id()]) == null
        }
    }

    /********** LIST / Draining Tests **********/

    def "LIST | should fully drain all projects sequentially using cursors"() {
        setup:
        def org = getRandomOrganization()
        (1..12).each { i ->
            def cmd = createProjectCommand(org.id(), "Cursor Drain Project ${String.format('%02d', i)}")
            client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", cmd)), Project)
        }
        Set<Project> allProjects = new LinkedHashSet<>()
        int pageSize = 5
        def pageable = CursoredPageable.from(pageSize, Sort.of(Sort.Order.asc("title")))

        when: "iterating through pages until no more data remains using projectController.getProjects"
        while (pageable != null) {
            CursoredPage<Project> page = projectController.getProjects(null, pageable)
            allProjects.addAll(page.content)
            pageable = page.hasNext() ? page.nextPageable() : null
        }

        then: "the collected set contains all projects from the database"
        def totalCount = sql.firstRow("SELECT count(*) as count FROM projects").count
        verifyAll {
            allProjects.size() == totalCount
            allProjects.size() >= 12
        }
    }

    def "LIST | should support HTTP pagination on GET /projects"() {
        setup:
        def org = getRandomOrganization()
        (1..5).each { i ->
            def cmd = createProjectCommand(org.id(), "Http Paging Project ${i}")
            client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", cmd)), Project)
        }

        when: "querying projects via HTTP GET"
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET("/projects?size=5")), Map)

        then: "the response is 200 OK with content list"
        Map body = response.body()
        verifyAll {
            response.status == HttpStatus.OK
            body.content instanceof List
            body.content.size() >= 5
        }
    }

    /********** Security 401 Negative Tests **********/

    @Unroll
    def "Security | should reject unauthenticated request with 401 UNAUTHORIZED for #method #uri"(String method, String uri) {
        when: "sending unauthenticated request"
        HttpRequest<?> req
        switch (method) {
            case "GET":
                req = HttpRequest.GET(uri)
                break
            case "POST":
                req = HttpRequest.POST(uri, [title: "Test", description: "At least 20 chars in description"])
                break
            case "PUT":
                req = HttpRequest.PUT(uri, [title: "Test", description: "At least 20 chars in description"])
                break
            case "DELETE":
                req = HttpRequest.DELETE(uri)
                break
            default:
                throw new IllegalArgumentException("Unsupported method ${method}")
        }
        client.exchange(req)

        then: "a 401 UNAUTHORIZED exception is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.UNAUTHORIZED

        where:
        method   | uri
        "GET"    | "/projects"
        "GET"    | "/projects/${UUID.randomUUID()}"
        "GET"    | "/projects/search-by-location?longitude=0&latitude=0&radiusMeters=1000"
        "POST"   | "/projects"
        "PUT"    | "/projects/${UUID.randomUUID()}"
        "DELETE" | "/projects/${UUID.randomUUID()}"
        "PUT"    | "/projects/${UUID.randomUUID()}/status"
    }
}
