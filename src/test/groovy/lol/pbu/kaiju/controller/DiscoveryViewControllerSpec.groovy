package lol.pbu.kaiju.controller

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.test.extensions.spock.annotation.MicronautTest

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
}
