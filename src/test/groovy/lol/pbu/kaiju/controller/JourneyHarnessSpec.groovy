package lol.pbu.kaiju.controller

import io.micronaut.core.type.Argument
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.runtime.server.EmbeddedServer
import jakarta.inject.Inject
import lol.pbu.kaiju.controller.JourneyHarnessController.HarnessLoginRequest
import lol.pbu.kaiju.controller.JourneyHarnessController.HarnessLoginResponse
import lol.pbu.kaiju.controller.JourneyHarnessController.HarnessPersona
import lol.pbu.kaiju.controller.JourneyHarnessController.HarnessSpecReport

class JourneyHarnessSpec extends BaseControllerSpec {

    @Inject
    EmbeddedServer embeddedServer

    def "GET / and GET /harness serve the interactive journey test harness UI with zero em dashes"() {
        when: "a visitor opens the root URL and the /harness URL without logging in"
        def rootResponse = client.exchange(HttpRequest.GET("/"), String)
        def harnessResponse = client.exchange(HttpRequest.GET("/harness"), String)

        then: "both routes return the interactive HTML harness for all 4 user journeys"
        verifyAll {
            rootResponse.status == HttpStatus.OK
            rootResponse.contentType.isPresent()
            rootResponse.contentType.get().toString().startsWith(MediaType.TEXT_HTML)
            rootResponse.body() != null
            rootResponse.body().contains("Kaiju")
            rootResponse.body().contains("Volunteer Discovers &amp; Commits")
            rootResponse.body().contains("Org Onboarding &amp; Project Wizard")
            rootResponse.body().contains("Specialist Reviews &amp; Approves")
            rootResponse.body().contains("Org Communicates with Volunteers")
            rootResponse.body().contains("/projects/search-by-location")
            rootResponse.body().contains("/shift-signups")
            rootResponse.body().contains("resolveHumanReadableLocation")
            !rootResponse.body().contains("id=\"j1-lat\"")
            !rootResponse.body().contains("id=\"j1-lon\"")
            !rootResponse.body().contains("id=\"j2-proj-lat\"")
            !rootResponse.body().contains("id=\"j2-proj-lon\"")
            !rootResponse.body().contains("\u2014")
            !rootResponse.body().contains("&mdash;")
            harnessResponse.status == HttpStatus.OK
            harnessResponse.body() == rootResponse.body()
        }
    }

    def "GET /harness/api/personas returns the seeded test personas for all four journeys"() {
        when: "the harness loads the persona list"
        def response = client.exchange(
                HttpRequest.GET("/harness/api/personas"),
                Argument.listOf(HarnessPersona)
        )
        List<HarnessPersona> personas = response.body()

        then: "all key personas from 09-seed-test-personas.sql and anonymous mode are present"
        verifyAll {
            response.status == HttpStatus.OK
            personas != null
            personas.size() == 10
            personas.any { it.email == "anonymous" && it.systemRole == "ANONYMOUS" && !it.seededInDb }
            personas.any { it.email == "victor.volunteer1@kaiju.local" && it.systemRole == "STANDARD_USER" && it.seededInDb }
            personas.any { it.email == "jenny.niu@kaiju.local" && !it.seededInDb }
            personas.any { it.email == "leo.local.admin1@kaiju.local" && it.organizationRole == "ORG_ADMIN" }
            personas.any { it.email == "daniel.county.director1@kaiju.local" && it.regionRole == "REGION_DIRECTOR" }
            personas.any { it.email == "ethan.sibling.director1@kaiju.local" && it.regionName.contains("El Paso County") }
            personas.any { it.email == "sarah.admin1@kaiju.local" && it.systemRole == "GLOBAL_ADMIN" }
            personas.every { !it.description.contains("\u2014") && !it.displayName.contains("\u2014") }
        }
    }

    def "GET /harness/api/journeys returns all 4 journeys and 34 steps from 05-key-user-journeys.adoc"() {
        when: "the harness loads the spec readiness report"
        def response = client.exchange(
                HttpRequest.GET("/harness/api/journeys"),
                HarnessSpecReport
        )
        HarnessSpecReport report = response.body()
        def allSteps = report.journeys().collectMany { it.steps() }

        then: "the report covers all 34 steps with accurate status counts and plainspoken assessments"
        verifyAll {
            response.status == HttpStatus.OK
            report != null
            report.specFile() == "docs/functional-spec/05-key-user-journeys.adoc"
            report.totalSteps() == 34
            report.readyCount() == 3
            report.partialCount() == 18
            report.missingCount() == 13
            report.journeys().size() == 4
            report.journeys()[0].journeyNumber() == 1
            report.journeys()[0].totalSteps() == 12
            report.journeys()[1].journeyNumber() == 2
            report.journeys()[1].totalSteps() == 9
            report.journeys()[2].journeyNumber() == 3
            report.journeys()[2].totalSteps() == 7
            report.journeys()[3].journeyNumber() == 4
            report.journeys()[3].totalSteps() == 6
            allSteps.size() == 34
            allSteps.any { it.stepType() == "DECISION" && it.stepId() == "1.6" }
            allSteps.any { it.stepType() == "FAILURE_STATE" && it.stepId() == "2.6" }
            allSteps.any { it.specRefs().contains("CUR-020") && it.specRefs().contains("ENH-015") }
            allSteps.any { it.specRefs().contains("CUR-042") }
            allSteps.any { it.specRefs().contains("ENH-038") }
            allSteps.any { it.specRefs().contains("CUR-047") }
            allSteps.every { !it.title().contains("\u2014") && !it.honestAssessment().contains("\u2014") }
        }
    }

    def "POST /harness/api/login supports anonymous mode and live Authentik persona login"() {
        when: "the tester switches to anonymous mode"
        def anonResponse = client.exchange(
                HttpRequest.POST("/harness/api/login", new HarnessLoginRequest("anonymous", null)),
                HarnessLoginResponse
        )
        HarnessLoginResponse anonBody = anonResponse.body()

        and: "the tester logs in as Victor Volunteer"
        def victorResponse = client.exchange(
                HttpRequest.POST("/harness/api/login", new HarnessLoginRequest("victor.volunteer1@kaiju.local", null)),
                HarnessLoginResponse
        )
        HarnessLoginResponse victorBody = victorResponse.body()

        then: "anonymous mode clears tokens and Victor login returns a valid Authentik OIDC token"
        verifyAll {
            embeddedServer.port > 0
            anonResponse.status == HttpStatus.OK
            anonBody != null
            !anonBody.authenticated()
            anonBody.email() == "anonymous"
            anonBody.token() == null
            !anonBody.message().contains("\u2014")
            victorResponse.status == HttpStatus.OK
            victorBody != null
            victorBody.authenticated()
            victorBody.email() == "victor.volunteer1@kaiju.local"
            victorBody.idToken() != null
            victorBody.token() != null
            !victorBody.token().isBlank()
            !victorBody.message().contains("\u2014")
        }
    }

    def "POST /harness/api/login rejects a blank email with 400 Bad Request"() {
        when: "the tester submits an empty email"
        client.exchange(
                HttpRequest.POST("/harness/api/login", new HarnessLoginRequest("   ", null)),
                HarnessLoginResponse
        )

        then: "a 400 Bad Request is returned"
        def ex = thrown(HttpClientResponseException)
        verifyAll {
            ex.status == HttpStatus.BAD_REQUEST
        }
    }
}
