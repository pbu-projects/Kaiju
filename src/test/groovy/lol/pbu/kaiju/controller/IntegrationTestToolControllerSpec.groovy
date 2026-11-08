package lol.pbu.kaiju.controller

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import lol.pbu.kaiju.controller.IntegrationTestToolController.LoginCommand
import lol.pbu.kaiju.controller.IntegrationTestToolController.PersonaDto

@MicronautTest(transactional = false)
class IntegrationTestToolControllerSpec extends BaseControllerSpec {

    def "anonymous caller can view the integration test tool at root path"() {
        when: "an anonymous user navigates to the root endpoint"
        def response = client.exchange(HttpRequest.GET("/"), String)

        then: "the integration test tool is returned successfully as HTML"
        verifyAll {
            response.status == HttpStatus.OK
            response.contentType.isPresent()
            response.contentType.get().toString().contains(MediaType.TEXT_HTML)
            response.body().contains("Kaiju Integration Testing Tool")
            response.body().contains("Manual Test Harness - Not Production UI")
            response.body().contains("Volunteer Discovers and Commits")
            response.body().contains("Street Address")
            response.body().contains("Project Details")
        }
    }

    def "anonymous caller can view the integration test tool at test tool path"() {
        when: "an anonymous user navigates to the /integration-test-tool endpoint"
        def response = client.exchange(HttpRequest.GET("/integration-test-tool"), String)

        then: "the integration test tool is returned successfully as HTML"
        verifyAll {
            response.status == HttpStatus.OK
            response.contentType.isPresent()
            response.contentType.get().toString().contains(MediaType.TEXT_HTML)
            response.body().contains("Kaiju Integration Testing Tool")
            response.body().contains("Specialist Reviews and Approves")
        }
    }

    def "anonymous caller can view the integration test tool at dashboard alias path"() {
        when: "an anonymous user navigates to the /dashboard endpoint"
        def response = client.exchange(HttpRequest.GET("/dashboard"), String)

        then: "the integration test tool is returned successfully as HTML"
        verifyAll {
            response.status == HttpStatus.OK
            response.contentType.isPresent()
            response.contentType.get().toString().contains(MediaType.TEXT_HTML)
            response.body().contains("Organization Posts a Project")
        }
    }

    def "anonymous caller can list available test personas"() {
        when: "requesting the pre-configured personas list"
        def response = client.exchange(HttpRequest.GET("/integration-test-tool/api/personas"), String)

        then: "all 3 test personas are returned"
        verifyAll {
            response.status == HttpStatus.OK
            response.body().contains("Victor Volunteer")
            response.body().contains("Jenny Niu")
            response.body().contains("Daniel Director")
        }
    }

    def "login endpoint validates required username"() {
        when: "attempting login with an empty command"
        client.exchange(HttpRequest.POST("/integration-test-tool/api/login", new LoginCommand("", "")))

        then: "a bad request response is returned"
        def ex = thrown(HttpClientResponseException)
        verifyAll {
            ex.status == HttpStatus.BAD_REQUEST
        }
    }
}
