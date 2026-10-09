package lol.pbu.kaiju.controller

import io.micronaut.context.annotation.Property
import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.data.model.Sort
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.uri.UriBuilder
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.domain.Project
import lol.pbu.kaiju.dto.CoordinateDto
import lol.pbu.kaiju.dto.CreateProjectCommand
import lol.pbu.kaiju.dto.ProjectBoundaryCommand
import lol.pbu.kaiju.dto.ProjectLocationCommand
import lol.pbu.kaiju.dto.UpdateProjectCommand
import lol.pbu.kaiju.model.ProjectStatus
import lol.pbu.kaiju.model.ProjectType
import lol.pbu.kaiju.repository.AdministrativeRegionRepository
import lol.pbu.kaiju.repository.OrganizationRepository
import lol.pbu.kaiju.repository.ProjectAuditLogRepository
import lol.pbu.kaiju.repository.ProjectRepository
import lol.pbu.kaiju.repository.UserRepository
import lol.pbu.kaiju.security.ProjectSecurityService
import net.datafaker.Faker
import spock.lang.Shared
import spock.lang.Unroll

import java.util.UUID

import static lol.pbu.kaiju.model.ProjectStatus.ACTIVE
import static lol.pbu.kaiju.model.ProjectStatus.DRAFT
import static lol.pbu.kaiju.model.ProjectStatus.PENDING
import static lol.pbu.kaiju.model.ProjectStatus.PENDING_UPDATE
import static lol.pbu.kaiju.model.ProjectType.STANDARD
import static lol.pbu.kaiju.model.VerificationStatus.UNVERIFIED

@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.oauth2.enabled", value = "false")
@Property(name = "micronaut.security.token.jwt.enabled", value = "false")
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
    String adminId = "00000000-0000-0000-0000-000000000000"

    def setupSpec() {
        executeUpdate("INSERT INTO users (id, email, role) VALUES ('00000000-0000-0000-0000-000000000000', 'test-principal@example.com', 'GLOBAL_ADMIN') ON CONFLICT DO NOTHING")
    }

    def cleanup() {
        executeUpdate("DELETE FROM shift_tags")
        executeUpdate("DELETE FROM shifts")
        executeUpdate("DELETE FROM project_audit_logs")
        executeUpdate("DELETE FROM project_locations")
        executeUpdate("DELETE FROM project_boundaries")
        executeUpdate("DELETE FROM project_users")
        executeUpdate("DELETE FROM projects")
        executeUpdate("DELETE FROM organization_regions")
        executeUpdate("DELETE FROM organization_users")
        executeUpdate("DELETE FROM region_users")
        executeUpdate("DELETE FROM locations WHERE name LIKE '%Loc%' OR name LIKE '%St%' OR address_line LIKE '%St%' OR city = 'Denver' OR city = 'Boulder' OR city = 'Toronto' OR city = 'Colo Springs'")
        executeUpdate("DELETE FROM administrative_regions WHERE name LIKE '%Region%'")
        executeUpdate("DELETE FROM organizations WHERE name LIKE '%Org%' OR name LIKE '%Denver%' OR name LIKE '%Boulder%' OR name LIKE '%Default%' OR name LIKE '%Test%'")
        executeUpdate("DELETE FROM users WHERE email LIKE '%@example.com' AND id != '00000000-0000-0000-0000-000000000000'")
    }

    def setup() {
        cleanup()
    }

    private Organization getRandomOrganization() {
        def orgRow = sql.firstRow("SELECT id, name FROM organizations LIMIT 1")
        if (orgRow) {
            return new Organization(orgRow.id as UUID, orgRow.name as String, null, null, true, UNVERIFIED, null, [])
        }
        def newId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Default Test Org', true, 'VERIFIED')", newId)
        return new Organization(newId, 'Default Test Org', null, null, true, UNVERIFIED, null, [])
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
        new CreateProjectCommand(
                orgId,
                managingRegionId,
                title,
                description,
                projectType,
                status,
                locations,
                boundaries
        )
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
        new UpdateProjectCommand(
                orgId,
                managingRegionId,
                title,
                description,
                projectType,
                locations,
                boundaries
        )
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
        response.status == HttpStatus.OK
        verifyAll {
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
        response.status == HttpStatus.OK
        saved.id() != null

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
        response.status == HttpStatus.OK
        verifyAll {
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
        response.status == HttpStatus.OK
        def content = response.body().content as List
        content != null
        content.any { it.title == targetTitle }
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
        response.status == HttpStatus.OK
        verifyAll {
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
        def orgAId = UUID.randomUUID()
        def orgBId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Org A', true)", orgAId)
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Org B', true)", orgBId)

        and: "a user who is an ORG_MANAGER of Org A only"
        def userId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "manager-a-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MANAGER')", userId, orgAId)

        and: "a project belonging to Org A"
        def projectId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Project in Org A', 'Description of project in Org A that is long enough.', 'STANDARD', 'DRAFT', NOW())
        """, projectId, orgAId)

        and: "an update command attempting to reassign the project to Org B"
        def updateCmd = updateProjectCommand(orgBId, "Hijacked Title", "Hijacked Description that is at least twenty chars long.")

        when: "the Org Manager attempts to reassign the project to Org B via HTTP PUT"
        client.exchange(authenticated(HttpRequest.PUT("/projects/${projectId}", updateCmd), userId.toString(), ["STANDARD_USER"]), Project)

        then: "the request is rejected with 403 Forbidden"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN

        and: "the project in the database remains assigned to Org A"
        def projectInDb = sql.firstRow("SELECT organization_id, title FROM projects WHERE id = ?", [projectId])
        projectInDb.organization_id == orgAId
        projectInDb.title == 'Project in Org A'
    }

    def "UPDATE | should allow legitimate update by Org Manager preserving existing organization"() {
        given: "an organization Org A"
        def orgAId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Org A', true)", orgAId)

        and: "a user who is an ORG_MANAGER of Org A"
        def userId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "manager-legit-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MANAGER')", userId, orgAId)

        and: "a project belonging to Org A"
        def projectId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Initial Title', 'Initial Description that is at least twenty chars long.', 'STANDARD', 'DRAFT', NOW())
        """, projectId, orgAId)

        and: "an update payload preserving Org A with updated title and description"
        def updateCmd = updateProjectCommand(orgAId, "Legit Updated Title", "Legit Updated Description that is at least twenty chars long.")

        when: "the Org Manager updates the project via HTTP PUT"
        def response = client.exchange(authenticated(HttpRequest.PUT("/projects/${projectId}", updateCmd), userId.toString(), ["STANDARD_USER"]), Project)
        Project updated = response.body()

        then: "the update succeeds"
        response.status == HttpStatus.OK
        updated.id() == projectId
        updated.title() == "Legit Updated Title"

        and: "the database reflects the updated fields while organization remains Org A"
        def projectInDb = sql.firstRow("SELECT organization_id, title, description FROM projects WHERE id = ?", [projectId])
        projectInDb.organization_id == orgAId
        projectInDb.title == "Legit Updated Title"
    }

    def "UPDATE | should allow project reassignment to another organization when user is GLOBAL_ADMIN"() {
        given: "two organizations Org A and Org B"
        def orgAId = UUID.randomUUID()
        def orgBId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Org A', true)", orgAId)
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Org B', true)", orgBId)

        and: "a project belonging to Org A"
        def projectId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Original Title', 'Original Description that is at least twenty chars long.', 'STANDARD', 'DRAFT', NOW())
        """, projectId, orgAId)

        and: "an update command reassigning the project to Org B"
        def updateCmd = updateProjectCommand(orgBId, "Admin Updated Title", "Admin Updated Description that is at least twenty chars long.")

        when: "the Global Admin reassigns the project to Org B via HTTP PUT"
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/projects/${projectId}", updateCmd)), Project)
        Project updated = response.body()

        then: "the update succeeds"
        response.status == HttpStatus.OK
        updated.id() == projectId
        updated.title() == "Admin Updated Title"

        and: "the project is reassigned to Org B in the database"
        def projectInDb = sql.firstRow("SELECT organization_id, title FROM projects WHERE id = ?", [projectId])
        projectInDb.organization_id == orgBId
        projectInDb.title == "Admin Updated Title"
    }

    def "UPDATE | should reject project reassignment even when user is manager of destination org"() {
        given: "two organizations Org A and Org B"
        def orgAId = UUID.randomUUID()
        def orgBId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Org A', true)", orgAId)
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Org B', true)", orgBId)

        and: "a user who is an ORG_MANAGER of both Org A and Org B"
        def dualManagerId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", dualManagerId, "dual-mgr-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MANAGER')", dualManagerId, orgAId)
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MANAGER')", dualManagerId, orgBId)

        and: "a project belonging to Org A"
        def projectId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Dual Mgr Project', 'Dual Mgr Description that is at least twenty chars long.', 'STANDARD', 'DRAFT', NOW())
        """, projectId, orgAId)

        and: "an update command reassigning the project to Org B"
        def updateCmd = updateProjectCommand(orgBId, "Reassigned by Dual Manager", "Desc with plenty of characters to pass validation.")

        when: "the dual manager attempts to reassign the project to Org B via HTTP PUT"
        client.exchange(authenticated(HttpRequest.PUT("/projects/${projectId}", updateCmd), dualManagerId.toString(), ["STANDARD_USER"]), Project)

        then: "the update is rejected with 403 Forbidden"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN

        and: "the project remains assigned to Org A in the database"
        def projectInDb = sql.firstRow("SELECT organization_id FROM projects WHERE id = ?", [projectId])
        projectInDb.organization_id == orgAId
    }

    def "UPDATE | should throw 404 Not Found when privileged user reassigns project to non-existent organization"() {
        given: "an organization Org A"
        def orgAId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Org A', true)", orgAId)

        and: "a project belonging to Org A"
        def projectId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Project to Reassign', 'Description of project to reassign at least 20 chars.', 'STANDARD', 'DRAFT', NOW())
        """, projectId, orgAId)

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
        def regionId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO administrative_regions (id, name, geom) 
            VALUES (?, 'City of Denver', ST_GeogFromText('POLYGON((-105.1099 39.7891, -104.7432 39.7912, -104.7528 39.6158, -105.0536 39.6137, -105.1099 39.7891))'))
        """, regionId)

        def orgId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Denver Org', true, 'VERIFIED')", orgId)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", orgId, regionId)

        and: "an Org Manager for the organization"
        def userId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", userId, "manager-demote-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MANAGER')", userId, orgId)

        and: "an ACTIVE project belonging to the organization"
        def projectId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Active Denver Project', 'Description of active Denver project at least 20 chars.', 'STANDARD', 'ACTIVE', NOW())
        """, projectId, orgId)

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
        def response = client.exchange(authenticated(HttpRequest.PUT("/projects/${projectId}", updateCmd), userId.toString(), ["STANDARD_USER"]), Project)
        Project updated = response.body()

        then: "the project status is demoted to PENDING for regional review"
        response.status == HttpStatus.OK
        updated.status() == PENDING

        and: "the status in database is PENDING"
        def projectInDb = sql.firstRow("SELECT status, title FROM projects WHERE id = ?", [projectId])
        projectInDb.status == 'PENDING'
        projectInDb.title == "Updated Active Project"
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
        response.status == HttpStatus.OK || response.status == HttpStatus.NO_CONTENT
        !projectRepository.findById(id).isPresent()
        sql.firstRow("SELECT count(*) as count FROM projects WHERE id = ?", [id]).count == 0
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
        def uuids = [:].withDefault { UUID.randomUUID() }

        String idName = "id, name"
        def insertInto = { String table, String columns, String values ->
            "INSERT INTO ${table} (${columns}) VALUES (${values})"
        }

        String insertOrganizationSql = insertInto("organizations", "${idName}, is_public", "?, 'Distance Test Org', true")
        String insertProjectSql = insertInto("projects", "id, organization_id, title, description, project_type, status, created_at", "?, ?, ?, 'Description with at least twenty characters here.', 'STANDARD', 'ACTIVE', NOW()")
        String insertLocationSql = insertInto("locations", "${idName}, address_line, city, country_code, geom", "?, ?, ?, ?, ?, ST_GeographyFromText(?)")
        String insertProjectLocationSql = insertInto("project_locations", "project_id, location_id", "?, ?")
        String insertShiftSql = insertInto("shifts", "id, project_id, is_virtual, location_id, start_time, end_time", "?, ?, false, ?, NOW() + INTERVAL '1 day', NOW() + INTERVAL '1 day 2 hours'")

        [
                [insertOrganizationSql, [uuids.organizationId]],
                [insertProjectSql, [uuids.projectIdA, uuids.organizationId, 'Project A']],
                [insertProjectSql, [uuids.projectIdB, uuids.organizationId, 'Project B']],
                [insertProjectSql, [uuids.projectIdC, uuids.organizationId, 'Project C']],
                [insertProjectSql, [uuids.projectIdD, uuids.organizationId, 'Project D']],
                [insertProjectSql, [uuids.projectIdE, uuids.organizationId, 'Project E']],

                [insertLocationSql, [uuids.locA1, 'Location A1', '123 Closest St', 'Denver', 'US', 'POINT(-104.9903 39.7572)']],
                [insertLocationSql, [uuids.locB1, 'Location B1', '456 Second St', 'Denver', 'US', 'POINT(-104.9903 39.7842)']],
                [insertLocationSql, [uuids.locA2, 'Location A2', '789 Third St', 'Denver', 'US', 'POINT(-104.9903 39.8292)']],
                [insertLocationSql, [uuids.locE1, 'Location E1', '101 Fourth St', 'Denver', 'US', 'POINT(-104.9903 39.8472)']],
                [insertLocationSql, [uuids.locC1, 'Location C1', '202 Fifth St', 'Denver', 'US', 'POINT(-104.9903 39.8742)']],
                [insertLocationSql, [uuids.locA3, 'Location A3', '303 Far St', 'Denver', 'US', 'POINT(-104.9903 40.1892)']],
                [insertLocationSql, [uuids.locD1, 'Location D1', '404 Far St', 'Denver', 'US', 'POINT(-104.9903 40.1892)']],

                [insertProjectLocationSql, [uuids.projectIdA, uuids.locA1]],
                [insertProjectLocationSql, [uuids.projectIdA, uuids.locA2]],
                [insertProjectLocationSql, [uuids.projectIdA, uuids.locA3]],
                [insertProjectLocationSql, [uuids.projectIdB, uuids.locB1]],
                [insertProjectLocationSql, [uuids.projectIdE, uuids.locE1]],
                [insertProjectLocationSql, [uuids.projectIdC, uuids.locC1]],
                [insertProjectLocationSql, [uuids.projectIdD, uuids.locD1]],

                [insertShiftSql, [uuids.shiftA1, uuids.projectIdA, uuids.locA1]],
                [insertShiftSql, [uuids.shiftA2, uuids.projectIdA, uuids.locA2]],
                [insertShiftSql, [uuids.shiftA3, uuids.projectIdA, uuids.locA3]],
                [insertShiftSql, [uuids.shiftB1, uuids.projectIdB, uuids.locB1]],
                [insertShiftSql, [uuids.shiftE1, uuids.projectIdE, uuids.locE1]],
                [insertShiftSql, [uuids.shiftC1, uuids.projectIdC, uuids.locC1]],
                [insertShiftSql, [uuids.shiftD1, uuids.projectIdD, uuids.locD1]]
        ].each { List<Object> seedStatement -> executeUpdate(seedStatement[0] as String, *(seedStatement[1] as List)) }

        when: "searching projects by location via HTTP GET with 20km radius"
        def uri = UriBuilder.of("/projects/search-by-location")
                .queryParam("longitude", -104.9903)
                .queryParam("latitude", 39.7392)
                .queryParam("radiusMeters", 20000.0)
                .queryParam("size", 100)
                .build().toString()
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET(uri)), Map)

        then: "only active projects in range are returned sorted by closest location, with no duplicates per project"
        response.status == HttpStatus.OK
        List items = response.body().content as List
        List testResults = items.findAll { (it.projectId as String) in [uuids.projectIdA, uuids.projectIdB, uuids.projectIdC, uuids.projectIdD, uuids.projectIdE].collect { it.toString() } }
        testResults.size() == 4

        testResults[0].projectId == uuids.projectIdA.toString()
        testResults[1].projectId == uuids.projectIdB.toString()
        testResults[2].projectId == uuids.projectIdE.toString()
        testResults[3].projectId == uuids.projectIdC.toString()
    }

    @Unroll
    def "SEARCH BY LOCATION | should handle project status #status and shift active state #shiftActive"() {
        given: "seed the database with test organization, project statuses, and shift time configurations"
        def uuids = [:].withDefault { UUID.randomUUID() }

        String idName = "id, name"
        def insertInto = { String table, String columns, String values ->
            "INSERT INTO ${table} (${columns}) VALUES (${values})"
        }

        String insertOrganizationSql = insertInto("organizations", "${idName}, is_public", "?, 'Status Test Org', true")
        String insertProjectSql = insertInto("projects", "id, organization_id, title, description, project_type, status, created_at", "?, ?, ?, 'Description with at least twenty characters here.', 'STANDARD', ?, NOW()")
        String insertLocationSql = insertInto("locations", "${idName}, address_line, city, country_code, geom", "?, ?, ?, ?, ?, ST_GeographyFromText(?)")
        String insertProjectLocationSql = insertInto("project_locations", "project_id, location_id", "?, ?")

        String startOffset = shiftActive ? "1 day" : "-1 day"
        String endOffset = shiftActive ? "1 day 2 hours" : "-22 hours"
        String insertShiftA1Sql = insertInto("shifts", "id, project_id, is_virtual, location_id, start_time, end_time", "?, ?, false, ?, NOW() + INTERVAL '${startOffset}', NOW() + INTERVAL '${endOffset}'")
        String insertShiftSql = insertInto("shifts", "id, project_id, is_virtual, location_id, start_time, end_time", "?, ?, false, ?, NOW() + INTERVAL '1 day', NOW() + INTERVAL '1 day 2 hours'")

        [
                [insertOrganizationSql, [uuids.organizationId]],
                [insertProjectSql, [uuids.projectIdA, uuids.organizationId, 'Project A', status]],
                [insertProjectSql, [uuids.projectIdB, uuids.organizationId, 'Project B', 'ACTIVE']],
                [insertLocationSql, [uuids.locA1, 'Location A1', '123 Closest St', 'Denver', 'US', 'POINT(-104.9903 39.7572)']],
                [insertLocationSql, [uuids.locB1, 'Location B1', '456 Second St', 'Denver', 'US', 'POINT(-104.9903 39.7842)']],
                [insertLocationSql, [uuids.locA2, 'Location A2', '789 Third St', 'Denver', 'US', 'POINT(-104.9903 39.8292)']],
                [insertProjectLocationSql, [uuids.projectIdA, uuids.locA1]],
                [insertProjectLocationSql, [uuids.projectIdA, uuids.locA2]],
                [insertProjectLocationSql, [uuids.projectIdB, uuids.locB1]],
                [insertShiftA1Sql, [uuids.shiftA1, uuids.projectIdA, uuids.locA1]],
                [insertShiftSql, [uuids.shiftA2, uuids.projectIdA, uuids.locA2]],
                [insertShiftSql, [uuids.shiftB1, uuids.projectIdB, uuids.locB1]]
        ].each { List<Object> seedStatement -> executeUpdate(seedStatement[0] as String, *(seedStatement[1] as List)) }

        when: "searching projects by location via HTTP GET"
        def uri = UriBuilder.of("/projects/search-by-location")
                .queryParam("longitude", -104.9903)
                .queryParam("latitude", 39.7392)
                .queryParam("radiusMeters", 20000.0)
                .queryParam("size", 100)
                .build().toString()
        def response = client.exchange(asGlobalAdmin(HttpRequest.GET(uri)), Map)

        then: "the result matches expected order and presence based on project status and shift validity"
        response.status == HttpStatus.OK
        List items = response.body().content as List
        List testResults = items.findAll { (it.projectId as String) in [uuids.projectIdA, uuids.projectIdB].collect { it.toString() } }

        List<String> expectedUUIDs = expectedOrderNames.collect { name ->
            (name == 'A' ? uuids.projectIdA : uuids.projectIdB).toString()
        }
        testResults.collect { it.projectId as String } == expectedUUIDs

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
        response.status == HttpStatus.OK
        def content = response.body().content as List
        content.isEmpty()
    }

    /********** APPROVE Tests **********/

    def "APPROVE | should allow GLOBAL_ADMIN to approve a virtual project"() {
        given: "an organization and a pending virtual project"
        def orgId = UUID.randomUUID()
        def projId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Approve Org', true)", orgId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Virtual Approve Project', 'Description with at least twenty characters here.', 'STANDARD', 'PENDING', NOW())
        """, projId, orgId)

        when: "the GLOBAL_ADMIN approves the project via HTTP PUT"
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/projects/${projId}/status", "")), Project)
        Project approved = response.body()

        then: "the project transitions to ACTIVE"
        response.status == HttpStatus.OK
        approved.id() == projId
        approved.status() == ACTIVE

        and: "the status is persisted in the database"
        def inDb = sql.firstRow("SELECT status FROM projects WHERE id = ?", [projId])
        inDb.status == 'ACTIVE'
    }

    def "APPROVE | should allow REGION_DIRECTOR to approve a virtual project when org is in their region"() {
        given: "a region, organization in that region, and pending virtual project"
        def regId = UUID.randomUUID()
        def orgId = UUID.randomUUID()
        def projId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Approve Region', ST_GeogFromText('POLYGON((-105.1 39.7, -104.7 39.7, -104.7 39.6, -105.1 39.6, -105.1 39.7))'))", regId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Director Org', true, 'VERIFIED')", orgId)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", orgId, regId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Director Virtual Project', 'Description with at least twenty characters here.', 'STANDARD', 'PENDING', NOW())
        """, projId, orgId)

        and: "a REGION_DIRECTOR of that region"
        def directorId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'REGION_DIRECTOR')", directorId, "dir-appr-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_DIRECTOR')", directorId, regId)

        when: "the REGION_DIRECTOR approves the virtual project via HTTP PUT"
        def response = client.exchange(authenticated(HttpRequest.PUT("/projects/${projId}/status", ""), directorId.toString(), ["REGION_DIRECTOR", "project:approve"]), Project)
        Project approved = response.body()

        then: "the project transitions to ACTIVE"
        response.status == HttpStatus.OK
        approved.id() == projId
        approved.status() == ACTIVE
    }

    def "APPROVE | should reject REGION_AGENT approval of virtual project with 403 Forbidden"() {
        given: "an organization and pending virtual project"
        def orgId = UUID.randomUUID()
        def projId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Agent VOrg', true)", orgId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Agent VProj', 'Description with at least twenty characters here.', 'STANDARD', 'PENDING', NOW())
        """, projId, orgId)

        and: "a REGION_AGENT user"
        def agentId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'REGION_AGENT')", agentId, "agent-appr-${UUID.randomUUID()}@example.com".toString())

        when: "the REGION_AGENT attempts to approve the virtual project via HTTP PUT"
        client.exchange(authenticated(HttpRequest.PUT("/projects/${projId}/status", ""), agentId.toString(), ["REGION_AGENT"]), Project)

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
        def orgId = UUID.randomUUID()
        def projId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Active Org', true)", orgId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Already Active', 'Description with at least twenty characters here.', 'STANDARD', 'ACTIVE', NOW())
        """, projId, orgId)

        when: "attempting to approve an ACTIVE project via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/projects/${projId}/status", "")), Project)

        then: "a 400 Bad Request is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
    }

    def "APPROVE | should allow approving a PENDING_UPDATE project"() {
        given: "a project in PENDING_UPDATE status"
        def orgId = UUID.randomUUID()
        def projId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'PendingUpdate Org', true)", orgId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Pending Update Proj', 'Description with at least twenty characters here.', 'STANDARD', 'PENDING_UPDATE', NOW())
        """, projId, orgId)

        when: "approving the PENDING_UPDATE project via HTTP PUT"
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/projects/${projId}/status", "")), Project)
        Project approved = response.body()

        then: "the project transitions to ACTIVE"
        response.status == HttpStatus.OK
        approved.status() == ACTIVE
    }

    def "APPROVE | should reject approval of soft-deleted project with 404 Not Found"() {
        given: "a soft-deleted pending project"
        def orgId = UUID.randomUUID()
        def projId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Deleted Org', true)", orgId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at, deleted_at)
            VALUES (?, ?, 'Deleted Proj', 'Description with at least twenty characters here.', 'STANDARD', 'PENDING', NOW(), NOW())
        """, projId, orgId)

        when: "attempting to approve a deleted project via HTTP PUT"
        client.exchange(asGlobalAdmin(HttpRequest.PUT("/projects/${projId}/status", "")), Project)

        then: "a 404 Not Found is thrown preventing resurrection"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
    }

    def "APPROVE | should write audit log record on approval"() {
        given: "a pending project and an admin"
        def orgId = UUID.randomUUID()
        def projId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Audit Org', true)", orgId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Audit Proj', 'Description with at least twenty characters here.', 'STANDARD', 'PENDING', NOW())
        """, projId, orgId)

        when: "approving the project via HTTP PUT"
        def response = client.exchange(asGlobalAdmin(HttpRequest.PUT("/projects/${projId}/status", "")), Project)

        then: "a project_audit_logs record is written"
        response.status == HttpStatus.OK
        def auditRow = sql.firstRow("SELECT action, actor_id FROM project_audit_logs WHERE project_id = ?", [projId])
        auditRow != null
        auditRow.action == 'APPROVED'
        auditRow.actor_id == UUID.fromString(adminId)
    }

    def "APPROVE | should allow REGION_DIRECTOR to approve physical project with locations inside their region"() {
        given: "a region, organization, location inside that region, and physical project"
        def regId = UUID.randomUUID()
        def orgId = UUID.randomUUID()
        def locId = UUID.randomUUID()
        def projId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Director Phys Region', ST_GeogFromText('POLYGON((-105.1 39.8, -104.7 39.8, -104.7 39.6, -105.1 39.6, -105.1 39.8))'))", regId)
        executeUpdate("INSERT INTO organizations (id, name, is_public) VALUES (?, 'Phys Org', true)", orgId)
        executeUpdate("INSERT INTO locations (id, name, address_line, city, country_code, geom) VALUES (?, 'Phys Loc', 'St', 'Denver', 'US', ST_GeographyFromText('POINT(-104.9903 39.7392)'))", locId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'Phys Project', 'Description with at least twenty characters here.', 'STANDARD', 'PENDING', NOW())
        """, projId, orgId)
        executeUpdate("INSERT INTO project_locations (project_id, location_id) VALUES (?, ?)", projId, locId)

        and: "a REGION_DIRECTOR assigned to that region"
        def directorId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'REGION_DIRECTOR')", directorId, "dir-phys-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_DIRECTOR')", directorId, regId)

        when: "the REGION_DIRECTOR approves the physical project via HTTP PUT"
        def response = client.exchange(authenticated(HttpRequest.PUT("/projects/${projId}/status", ""), directorId.toString(), ["REGION_DIRECTOR", "project:approve"]), Project)
        Project approved = response.body()

        then: "the physical project transitions to ACTIVE"
        response.status == HttpStatus.OK
        approved.id() == projId
        approved.status() == ACTIVE
    }

    def "APPROVE | should allow REGION_DIRECTOR to approve virtual project assigned via managing_region_id"() {
        given: "a region, verified organization, and virtual project explicitly assigned via managing_region_id"
        def regId = UUID.randomUUID()
        def orgId = UUID.randomUUID()
        def projId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Director Managing Reg', ST_GeogFromText('POLYGON((-105.1 39.8, -104.7 39.8, -104.7 39.6, -105.1 39.6, -105.1 39.8))'))", regId)
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Managing Reg Org', true, 'VERIFIED')", orgId)
        executeUpdate("""
            INSERT INTO projects (id, organization_id, managing_region_id, title, description, project_type, status, created_at)
            VALUES (?, ?, ?, 'Virtual Assigned Project', 'Description with at least twenty characters here.', 'STANDARD', 'PENDING', NOW())
        """, projId, orgId, regId)

        and: "a REGION_DIRECTOR assigned to that managing region"
        def directorId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'REGION_DIRECTOR')", directorId, "dir-mgr-appr-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_DIRECTOR')", directorId, regId)

        when: "the REGION_DIRECTOR approves the virtual project via HTTP PUT"
        def response = client.exchange(authenticated(HttpRequest.PUT("/projects/${projId}/status", ""), directorId.toString(), ["REGION_DIRECTOR", "project:approve"]), Project)
        Project approved = response.body()

        then: "the project transitions to ACTIVE"
        response.status == HttpStatus.OK
        approved.id() == projId
        approved.status() == ACTIVE
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
        given: "a Denver region and a Region Director for Denver"
        def denverRegId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO administrative_regions (id, name, geom)
            VALUES (?, 'Denver Approval Region', ST_GeogFromText('POLYGON((-105.1099 39.7891, -104.7432 39.7912, -104.7528 39.6158, -105.0536 39.6137, -105.1099 39.7891))'))
        """, denverRegId)

        def directorId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'REGION_DIRECTOR')", directorId, "dir-denver-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO region_users (user_id, region_id, role) VALUES (?, ?, 'REGION_DIRECTOR')", directorId, denverRegId)

        and: "a PENDING project with physical locations in Colorado Springs (outside Denver)"
        def org = getRandomOrganization()
        def csLocId = UUID.randomUUID()
        def projId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO projects (id, organization_id, title, description, project_type, status, created_at)
            VALUES (?, ?, 'CS Project', 'Description with at least twenty characters here.', 'STANDARD', 'PENDING', NOW())
        """, projId, org.id())
        executeUpdate("INSERT INTO locations (id, name, address_line, city, country_code, geom) VALUES (?, 'CS Loc', '123 Main', 'Colo Springs', 'US', ST_GeogFromText('POINT(-104.82 38.83)'))",
                csLocId)
        executeUpdate("INSERT INTO project_locations (project_id, location_id) VALUES (?, ?)", projId, csLocId)

        when: "the Denver Region Director attempts to approve the project via HTTP PUT"
        client.exchange(authenticated(HttpRequest.PUT("/projects/${projId}/status", ""), directorId.toString(), ["REGION_DIRECTOR", "project:approve"]), Project)

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "APPROVE | should throw 404 Not Found when approving principal does not exist in users table"() {
        given: "a PENDING project"
        def org = getRandomOrganization()
        def command = createProjectCommand(org.id(), "Actor 404 Project", "Description with at least twenty characters here.", STANDARD, PENDING)
        Project saved = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", command)), Project).body()

        when: "calling approveProject with a principal UUID that is not in the users table via HTTP PUT"
        def nonExistentUserId = UUID.randomUUID()
        client.exchange(authenticated(HttpRequest.PUT("/projects/${saved.id()}/status", ""), nonExistentUserId.toString(), ["GLOBAL_ADMIN", "project:approve"]), Project)

        then: "a 404 Not Found is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
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
        def regId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Strict Region', ST_GeogFromText('POLYGON((-105.1 39.8, -104.7 39.8, -104.7 39.6, -105.1 39.6, -105.1 39.8))'))", regId)

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
        def regId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Auth Region', ST_GeogFromText('POLYGON((-105.1 39.8, -104.7 39.8, -104.7 39.6, -105.1 39.6, -105.1 39.8))'))", regId)

        and: "an organization and an unauthorized standard user"
        def org = getRandomOrganization()
        def unauthUserId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", unauthUserId, "unauth-${UUID.randomUUID()}@example.com".toString())

        def command = createProjectCommand(org.id(), "Unauthorized Region Project", "Description with at least twenty characters here.", STANDARD, DRAFT, regId)

        when: "submitting the project via HTTP POST by unauthorized user"
        client.exchange(authenticated(HttpRequest.POST("/projects", command), unauthUserId.toString(), ["STANDARD_USER"]), Project)

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "SUBMIT | should successfully save virtual project with valid managingRegion"() {
        given: "a valid region and organization where user has authority"
        UUID regId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO administrative_regions (id, name, geom)
            VALUES (?, 'Submit Virtual Region', ST_GeogFromText('POLYGON((-105.1 39.7, -104.9 39.7, -104.9 39.8, -105.1 39.8, -105.1 39.7))'))
        """, regId)
        def org = getRandomOrganization()
        def command = createProjectCommand(org.id(), "Virtual Region Proj", "Description with at least twenty characters here.", STANDARD, DRAFT, regId)

        when: "submitting virtual project with global admin via HTTP POST"
        def response = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", command)), Project)
        Project saved = response.body()

        then: "project is saved and managingRegion is set"
        response.status == HttpStatus.OK
        saved.id() != null
        saved.managingRegion() != null
        saved.managingRegion().id() == regId
    }

    def "UPDATE | should throw 400 when updating project locations outside existing managingRegion"() {
        given: "a managing region and an active project inside it"
        def regId = UUID.randomUUID()
        executeUpdate("INSERT INTO administrative_regions (id, name, geom) VALUES (?, 'Update Region', ST_GeogFromText('POLYGON((-105.1 39.8, -104.7 39.8, -104.7 39.6, -105.1 39.6, -105.1 39.8))'))", regId)
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
        given: "an organization with a manager and a separate foreign region"
        def orgId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Update Region Org', true, 'VERIFIED')", orgId)

        def foreignRegionId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO administrative_regions (id, name, geom)
            VALUES (?, 'Foreign Region', ST_GeogFromText('POLYGON((-105.1 39.7, -104.9 39.7, -104.9 39.8, -105.1 39.8, -105.1 39.7))'))
        """, foreignRegionId)

        def managerId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", managerId, "mgr-unauth-reg-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MANAGER')", managerId, orgId)

        def createCmd = createProjectCommand(orgId, "Unauth Region Proj", "Description with at least twenty characters here.", STANDARD, DRAFT)
        Project saved = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", createCmd)), Project).body()

        when: "the Org Manager attempts to assign a foreign region not linked to their organization via HTTP PUT"
        def updateCmd = updateProjectCommand(orgId, "Unauth Region Proj", "Description with at least twenty characters here.", STANDARD, foreignRegionId)
        client.exchange(authenticated(HttpRequest.PUT("/projects/${saved.id()}", updateCmd), managerId.toString(), ["STANDARD_USER"]), Project)

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "UPDATE | should throw 403 when unauthorized user attempts to unassign managingRegion"() {
        given: "a project with a managingRegion in Region B under an organization not bound to Region B"
        def regBId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO administrative_regions (id, name, geom)
            VALUES (?, 'Region B', ST_GeogFromText('POLYGON((-104.8 39.7, -104.6 39.7, -104.6 39.8, -104.8 39.8, -104.8 39.7))'))
        """, regBId)

        def orgId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Org Unassign Region', true, 'VERIFIED')", orgId)

        def managerId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", managerId, "mgr-unauth-unassign-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MANAGER')", managerId, orgId)

        def createCmd = createProjectCommand(orgId, "Project with Region B", "Description with at least twenty characters here.", STANDARD, DRAFT, regBId)
        Project saved = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", createCmd)), Project).body()

        when: "the Org Manager attempts to unassign the managingRegion via HTTP PUT"
        def updateCmd = updateProjectCommand(orgId, "Project with Region B", "Description with at least twenty characters here.", STANDARD, null)
        client.exchange(authenticated(HttpRequest.PUT("/projects/${saved.id()}", updateCmd), managerId.toString(), ["STANDARD_USER"]), Project)

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "UPDATE | should allow authorized Org Manager to unassign managingRegion"() {
        given: "a region and an organization bounded to it with an Org Manager"
        def regId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO administrative_regions (id, name, geom)
            VALUES (?, 'Region For Unassign', ST_GeogFromText('POLYGON((-105.1 39.7, -104.9 39.7, -104.9 39.8, -105.1 39.8, -105.1 39.7))'))
        """, regId)

        def orgId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Org For Unassign', true, 'VERIFIED')", orgId)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", orgId, regId)

        def managerId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", managerId, "mgr-unassign-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MANAGER')", managerId, orgId)

        def createCmd = createProjectCommand(orgId, "Project with Region", "Description with at least twenty characters here.", STANDARD, DRAFT, regId)
        Project saved = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", createCmd)), Project).body()

        when: "the Org Manager updates the project setting managingRegion to null via HTTP PUT"
        def updateCmd = updateProjectCommand(orgId, "Project with Region", "Description with at least twenty characters here.", STANDARD, null)
        def response = client.exchange(authenticated(HttpRequest.PUT("/projects/${saved.id()}", updateCmd), managerId.toString(), ["STANDARD_USER"]), Project)
        Project updated = response.body()

        then: "managingRegion is successfully unassigned"
        response.status == HttpStatus.OK
        updated.managingRegion() == null
        def dbRow = sql.firstRow("SELECT managing_region_id FROM projects WHERE id = ?", [saved.id()])
        dbRow.managing_region_id == null
    }

    def "UPDATE | should demote ACTIVE project to PENDING when reassigned to organization outside geographic bounds"() {
        given: "two regions: Denver and Boulder"
        def denverRegId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO administrative_regions (id, name, geom)
            VALUES (?, 'Denver City Reassign', ST_GeogFromText('POLYGON((-105.1099 39.7891, -104.7432 39.7912, -104.7528 39.6158, -105.0536 39.6137, -105.1099 39.7891))'))
        """, denverRegId)

        def boulderRegId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO administrative_regions (id, name, geom)
            VALUES (?, 'Boulder City Reassign', ST_GeogFromText('POLYGON((-105.35 39.95, -105.15 39.95, -105.15 40.10, -105.35 40.10, -105.35 39.95))'))
        """, boulderRegId)

        def orgAId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Denver Reassign Org A', true, 'VERIFIED')", orgAId)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", orgAId, denverRegId)

        def orgBId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Boulder Reassign Org B', true, 'VERIFIED')", orgBId)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", orgBId, boulderRegId)

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
        response.status == HttpStatus.OK
        updated.status() == PENDING
        def row = sql.firstRow("SELECT status, organization_id FROM projects WHERE id = ?", [saved.id()])
        row.status == 'PENDING'
        row.organization_id == orgBId
    }

    def "UPDATE | should preserve ACTIVE status when reassigned to organization containing geographic bounds"() {
        given: "a shared regional boundary enclosing Denver"
        def regionId = UUID.randomUUID()
        executeUpdate("""
            INSERT INTO administrative_regions (id, name, geom)
            VALUES (?, 'Shared Denver Region', ST_GeogFromText('POLYGON((-105.1099 39.7891, -104.7432 39.7912, -104.7528 39.6158, -105.0536 39.6137, -105.1099 39.7891))'))
        """, regionId)

        def org1Id = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Org Alpha Shared', true, 'VERIFIED')", org1Id)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", org1Id, regionId)

        def org2Id = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Org Beta Shared', true, 'VERIFIED')", org2Id)
        executeUpdate("INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?)", org2Id, regionId)

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
        response.status == HttpStatus.OK
        updated.status() == ACTIVE
        def row = sql.firstRow("SELECT status, organization_id FROM projects WHERE id = ?", [saved.id()])
        row.status == 'ACTIVE'
        row.organization_id == org2Id
    }

    def "UPDATE | should reject project update by unaffiliated standard user with 403 Forbidden"() {
        given: "an existing project and an unaffiliated standard user"
        def org = getRandomOrganization()
        def createCmd = createProjectCommand(org.id(), "Protected Project", "Description with at least twenty characters here.")
        Project saved = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", createCmd)), Project).body()

        def unauthUserId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", unauthUserId, "unaffil-update-${UUID.randomUUID()}@example.com".toString())

        when: "unaffiliated user attempts to update the project via HTTP PUT"
        def updateCmd = updateProjectCommand(org.id(), "Tampered Title", "Description with at least twenty characters here.")
        client.exchange(authenticated(HttpRequest.PUT("/projects/${saved.id()}", updateCmd), unauthUserId.toString(), ["STANDARD_USER"]), Project)

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "DELETE | should reject project deletion by unaffiliated user with 403 Forbidden"() {
        given: "an existing project and an unaffiliated standard user"
        def org = getRandomOrganization()
        def createCmd = createProjectCommand(org.id(), "Protected Delete Proj", "Description with at least twenty characters here.")
        Project saved = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", createCmd)), Project).body()

        def unauthUserId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", unauthUserId, "unaffil-del-${UUID.randomUUID()}@example.com".toString())

        when: "unaffiliated user attempts to delete the project via HTTP DELETE"
        client.exchange(authenticated(HttpRequest.DELETE("/projects/${saved.id()}"), unauthUserId.toString(), ["STANDARD_USER"]))

        then: "a 403 Forbidden is thrown"
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.FORBIDDEN
    }

    def "DELETE | should allow Org Manager to delete project belonging to their organization"() {
        given: "an organization with an Org Manager and a project"
        def orgId = UUID.randomUUID()
        executeUpdate("INSERT INTO organizations (id, name, is_public, verification_status) VALUES (?, 'Delete Test Org', true, 'VERIFIED')", orgId)

        def managerId = UUID.randomUUID()
        executeUpdate("INSERT INTO users (id, email, role) VALUES (?, ?, 'STANDARD_USER')", managerId, "mgr-del-${UUID.randomUUID()}@example.com".toString())
        executeUpdate("INSERT INTO organization_users (user_id, organization_id, role) VALUES (?, ?, 'ORG_MANAGER')", managerId, orgId)

        def createCmd = createProjectCommand(orgId, "Project to Delete", "Description with at least twenty characters here.")
        Project saved = client.exchange(asGlobalAdmin(HttpRequest.POST("/projects", createCmd)), Project).body()

        when: "the Org Manager deletes the project via HTTP DELETE"
        def response = client.exchange(authenticated(HttpRequest.DELETE("/projects/${saved.id()}"), managerId.toString(), ["STANDARD_USER"]))

        then: "the project is deleted from the database"
        response.status == HttpStatus.OK || response.status == HttpStatus.NO_CONTENT
        sql.firstRow("SELECT id FROM projects WHERE id = ?", [saved.id()]) == null
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
        response.status == HttpStatus.OK
        Map body = response.body()
        body.content instanceof List
        body.content.size() >= 5
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
