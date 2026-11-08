package lol.pbu.kaiju.controller

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.test.extensions.spock.annotation.MicronautTest

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
            response.body().contains("Manual Test Harness - Not Production UI")
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
            response.body().contains("Kaiju Integration Testing Tool")
            response.body().contains("Manual Test Harness - Not Production UI")
            response.body().contains("Organization Posts a Project")
        }
    }
}
