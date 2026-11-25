package lol.pbu.kaiju.controller

import io.micronaut.core.io.Writable
import io.micronaut.data.model.CursoredPage
import io.micronaut.data.model.CursoredPageable
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.views.ViewsRenderer
import lol.pbu.kaiju.domain.AdministrativeRegion
import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.domain.Project
import lol.pbu.kaiju.model.FeaturedProjectView
import lol.pbu.kaiju.model.ProjectStatus
import lol.pbu.kaiju.model.ProjectType
import lol.pbu.kaiju.model.VerificationStatus
import lol.pbu.kaiju.repository.ProjectRepository
import spock.lang.Unroll

@MicronautTest(transactional = false)
class DiscoveryViewControllerSpec extends BaseControllerSpec {

    def "anonymous visitor loads landing page at root path"() {
        when: "an anonymous visitor requests GET / with Accept text/html"
        HttpResponse<String> response = client.exchange(
                HttpRequest.GET("/").accept(MediaType.TEXT_HTML),
                String
        )
        String html = response.body()

        then: "it returns 200 OK with landing page content and no live feed"
        verifyAll {
            response.status == HttpStatus.OK
            response.contentType.isPresent()
            response.contentType.get().toString().contains(MediaType.TEXT_HTML)
            html != null
            html.contains("/css/volunteer-monster.css")
            html.contains("Meaningful service, matched to what you bring.")
            html.contains('action="/projects"')
            html.contains('name="q"')
            html.contains('name="location"')
            html.contains("/projects?type=IN_PERSON")
            html.contains("/projects?type=DONATE")
            html.contains("/projects?type=REMOTE")
            html.contains("Filter openly")
            html.contains("Claim with email")
            html.contains("Show up ready")
            !html.contains("Example Listing")
            !html.contains('id="scheduled-shifts-queue"')
        }
    }

    def "anonymous visitor loads full discovery page at /projects"() {
        when: "an anonymous visitor requests GET /projects with Accept text/html"
        HttpResponse<String> response = client.exchange(
                HttpRequest.GET("/projects").accept(MediaType.TEXT_HTML),
                String
        )
        String html = response.body()

        then: "it returns 200 OK with full discovery page markup and search controls"
        verifyAll {
            response.status == HttpStatus.OK
            response.contentType.isPresent()
            response.contentType.get().toString().contains(MediaType.TEXT_HTML)
            html != null
            html.contains("<!DOCTYPE html>")
            html.contains('id="where-input"')
            html.contains('id="radius-select"')
            html.contains('id="when-select"')
            html.contains('id="interest-input"')
            html.contains('id="interest-tag-strip"')
            html.contains("Food &amp; Hunger")
            html.contains("Civic Clean-Up")
            html.contains("Traveler &amp; Shelter Outreach")
            html.contains("Environment &amp; Trails")
            html.contains("Nearest (Location)")
            html.contains("Soonest (Shift Date)")
            html.contains('id="scheduled-shifts-queue"')
            html.contains('id="related-opportunities-queue"')
            html.contains("Related: Open Door &amp; Regional Nearby")
            html.contains("nominatim.openstreetmap.org/search")
            html.contains("/projects/search-by-location")
        }
    }

    def "htmx request to /projects returns partial discovery fragment"() {
        when: "an HTMX request is sent to GET /projects with HX-Request true"
        HttpResponse<String> response = client.exchange(
                HttpRequest.GET("/projects")
                        .accept(MediaType.TEXT_HTML)
                        .header("HX-Request", "true"),
                String
        )
        String html = response.body()

        then: "it returns 200 OK with the partial results fragment and no doctype"
        verifyAll {
            response.status == HttpStatus.OK
            response.contentType.isPresent()
            response.contentType.get().toString().contains(MediaType.TEXT_HTML)
            html != null
            !html.contains("<!DOCTYPE html>")
            html.contains('id="discovery-results-shell"')
            html.contains('id="scheduled-shifts-queue"')
            html.contains('id="related-opportunities-queue"')
        }
    }

    def "json api call to /projects preserves ProjectController response"() {
        when: "a JSON API client requests GET /projects without Accept text/html"
        HttpResponse<Map> response = client.exchange(
                HttpRequest.GET("/projects"),
                Map
        )
        Map body = response.body()

        then: "it returns 200 OK with JSON containing a content key"
        verifyAll {
            response.status == HttpStatus.OK
            body != null
            body.containsKey("content")
        }
    }

    def "anonymous visitor can load volunteer-monster stylesheet"() {
        when: "an anonymous visitor requests GET /css/volunteer-monster.css"
        HttpResponse<String> response = client.exchange(
                HttpRequest.GET("/css/volunteer-monster.css"),
                String
        )
        String css = response.body()

        then: "it returns 200 OK and contains the primary action color variable"
        verifyAll {
            response.status == HttpStatus.OK
            css != null
            css.contains("--action-primary: #4A2545")
        }
    }

    private FeaturedProjectView renderFeaturedProject(Project project) {
        Map<String, Object> capturedModel = [:]
        ViewsRenderer<Map<String, Object>, HttpRequest<?>> renderer = Mock(ViewsRenderer) {
            render("index", _ as Map, _ as HttpRequest) >> { String view, Map<String, Object> model, HttpRequest<?> req ->
                capturedModel.putAll(model)
                return ({ Writer out -> } as Writable)
            }
        }
        CursoredPage<Project> page = project == null
                ? Mock(CursoredPage) { isEmpty() >> true; getContent() >> [] }
                : Mock(CursoredPage) { isEmpty() >> false; getContent() >> [project] }
        ProjectRepository repo = Mock(ProjectRepository) {
            findByStatusAndDeletedAtIsNull(ProjectStatus.ACTIVE, _ as CursoredPageable) >> page
        }
        DiscoveryViewController controller = new DiscoveryViewController(renderer, repo)
        HttpResponse<Writable> response = controller.index(HttpRequest.GET("/"))
        assert response.status == HttpStatus.OK
        return capturedModel.get("featuredProject") as FeaturedProjectView
    }

    private static Organization orgWith(String name, VerificationStatus status) {
        return new Organization(UUID.randomUUID(), name, null, null, true, status, null, [])
    }

    private static Location locationWith(String name, String city, String stateProvince) {
        return new Location(UUID.randomUUID(), name, "123 Main St", city, stateProvince, "80202", "US", null, null)
    }

    private static AdministrativeRegion regionWith(String name) {
        return new AdministrativeRegion(UUID.randomUUID(), name, null, null)
    }

    private static Project projectWith(
            Organization org,
            AdministrativeRegion region,
            ProjectType type,
            List<Location> locations
    ) {
        return new Project(
                UUID.randomUUID(),
                org,
                region,
                "Community Pantry",
                "Help pack and distribute weekly groceries.",
                type,
                ProjectStatus.ACTIVE,
                null,
                null,
                null,
                locations,
                []
        )
    }

    def "landing page handles empty active project results gracefully"() {
        when: "no active projects exist"
        FeaturedProjectView view = renderFeaturedProject(null)

        then: "featuredProject is null"
        view == null
    }

    @Unroll
    def "landing page formats project type #projectType as '#expectedLabel'"(ProjectType projectType, String expectedLabel) {
        given: "a project with the target project type"
        Project project = projectWith(orgWith("Hope Kitchen", VerificationStatus.VERIFIED), null, projectType, [])

        when: "rendering the landing page model"
        FeaturedProjectView view = renderFeaturedProject(project)

        then: "the project type label and verified status match"
        verifyAll(view) {
            id() == project.id()
            title() == project.title()
            description() == project.description()
            projectTypeLabel() == expectedLabel
            organizationName() == "Hope Kitchen"
            verified()
        }

        where:
        projectType           | expectedLabel
        null                  | ""
        ProjectType.STANDARD  | "Standard"
        ProjectType.OPEN_DOOR | "Open Door"
        ProjectType.REGIONAL  | "Regional"
    }

    @Unroll
    def "landing page defaults organizationName and verified when #caseName"(
            String caseName,
            Organization organization
    ) {
        given: "a project with #caseName"
        Project project = projectWith(organization, null, ProjectType.STANDARD, [])

        when: "rendering the landing page model"
        FeaturedProjectView view = renderFeaturedProject(project)

        then: "organizationName defaults to empty string and verified is false"
        verifyAll(view) {
            organizationName() == ""
            !verified()
        }

        where:
        caseName                       | organization
        "organization is null"         | null
        "organization name is missing" | orgWith(null, VerificationStatus.UNVERIFIED)
    }

    @Unroll
    def "landing page formats location label for #caseName"(
            String caseName,
            List<Location> locations,
            AdministrativeRegion region,
            String expectedLabel
    ) {
        given: "a project with the given locations and managing region"
        Project project = projectWith(orgWith("Metro Aid", VerificationStatus.VERIFIED), region, ProjectType.STANDARD, locations)

        when: "rendering the landing page model"
        FeaturedProjectView view = renderFeaturedProject(project)

        then: "locationLabel matches the expected fallback"
        view.locationLabel() == expectedLabel

        where:
        caseName                         | locations                                  | region                  | expectedLabel
        "city and state"                 | [locationWith("Hub", "Denver", "CO")]      | regionWith("Colorado")  | "Denver, CO"
        "city with null state"           | [locationWith("Hub", "Denver", null)]      | regionWith("Colorado")  | "Denver"
        "city with blank state"          | [locationWith("Hub", "Denver", "   ")]     | regionWith("Colorado")  | "Denver"
        "null city with location name"   | [locationWith("Main Hub", null, "CO")]     | regionWith("Colorado")  | "Main Hub"
        "blank city with location name"  | [locationWith("Main Hub", "  ", "CO")]     | regionWith("Colorado")  | "Main Hub"
        "null city and blank name"       | [locationWith("  ", null, "CO")]           | regionWith("Colorado")  | "Colorado"
        "null city and null name"        | [locationWith(null, null, "CO")]           | regionWith("Colorado")  | "Colorado"
        "null locations with region"     | null                                       | regionWith("Front Range") | "Front Range"
        "empty locations with region"    | []                                         | regionWith("Front Range") | "Front Range"
        "empty locations null region name" | []                                       | regionWith(null)        | ""
        "empty locations and null region"  | []                                       | null                    | ""
    }
}
