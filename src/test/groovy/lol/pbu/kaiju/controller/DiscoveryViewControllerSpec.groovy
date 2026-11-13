package lol.pbu.kaiju.controller

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.test.extensions.spock.annotation.MicronautTest

@MicronautTest(transactional = false)
class DiscoveryViewControllerSpec extends BaseControllerSpec {

    def "anonymous visitor can load the volunteer project discovery home page at root path"() {
        when: "an anonymous visitor requests the home page at GET /"
        HttpResponse<String> response = client.exchange(
                HttpRequest.GET("/").accept(MediaType.TEXT_HTML),
                String
        )
        String html = response.body()

        then: "the page loads with 200 OK and renders the discovery queues, 3-part search bar, and sort controls"
        verifyAll {
            response.status == HttpStatus.OK
            response.contentType.isPresent()
            response.contentType.get().toString().contains(MediaType.TEXT_HTML)
            html != null
            html.contains('id="where-input"')
            html.contains('id="radius-select"')
            html.contains('id="when-select"')
            html.contains('id="interest-input"')
            html.contains('id="interest-tag-strip"')
            html.contains('Food &amp; Hunger')
            html.contains('Civic Clean-Up')
            html.contains('Traveler &amp; Shelter Outreach')
            html.contains('Environment &amp; Trails')
            html.contains('Nearest (Location)')
            html.contains('Soonest (Shift Date)')
            html.contains('id="scheduled-shifts-queue"')
            html.contains('id="related-opportunities-queue"')
            html.contains('Related: Open Door &amp; Regional Nearby')
            html.contains('nominatim.openstreetmap.org/search')
            html.contains('/projects/search-by-location')
        }
    }

    def "throws internal server error when template is missing"() {
        when: "controller attempts to load a non-existent template resource"
        new DiscoveryViewController("/views/non-existent-template.html")

        then: "an HttpStatusException is thrown with INTERNAL_SERVER_ERROR"
        HttpStatusException ex = thrown(HttpStatusException)
        verifyAll(ex) {
            status == HttpStatus.INTERNAL_SERVER_ERROR
            message.contains("Discovery view template not found")
        }
    }

    def "loads template from classpath fallback when local file is not on disk"() {
        when: "controller loads a classpath resource that does not exist in src/main/resources"
        DiscoveryViewController controller = new DiscoveryViewController("/application-test.yml")
        HttpResponse<String> response = controller.index()

        then: "the template content is read from the classpath stream"
        verifyAll {
            response.status == HttpStatus.OK
            response.body() != null
            response.body().contains("volunteer_monster")
        }
    }
}
