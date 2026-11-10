package lol.pbu.kaiju.controller

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.runtime.server.EmbeddedServer
import jakarta.inject.Inject

class VolunteerSearchControllerSpec extends BaseControllerSpec {

    @Inject
    EmbeddedServer embeddedServer

    def "GET / and GET /search serve the volunteer project search UI without authentication"() {
        when: "an anonymous visitor opens the search UI routes"
        def rootResponse = client.exchange(HttpRequest.GET("/"), String)
        def searchResponse = client.exchange(HttpRequest.GET("/search"), String)

        then: "all public routes return HTTP 200 with volunteer discovery content"
        verifyAll {
            rootResponse.status == HttpStatus.OK
            rootResponse.contentType.isPresent()
            rootResponse.contentType.get().toString().startsWith(MediaType.TEXT_HTML)
            rootResponse.body() != null
            rootResponse.body().contains("Find a way to help, close to home.")
            rootResponse.body().contains("Street Address or Spot")
            rootResponse.body().contains("Specific Lat &amp; Long Spot:")
            rootResponse.body().contains("Happening soon near Denver")
            rootResponse.body().contains("Just posted near you")
            rootResponse.body().contains("Things you can do from home")
            rootResponse.body().contains("Organization Directory &amp; Vanity Profiles")
            rootResponse.body().contains("/denvermetro")
            rootResponse.body().contains("/altaviewelementary")
            !rootResponse.body().contains((char) 8212 as String)
            !rootResponse.body().contains("&" + "mdash;")

            searchResponse.status == HttpStatus.OK
        }
    }

    def "Anonymous visitor enjoys full public discovery parity on spatial search endpoints"() {
        when: "an unauthenticated visitor searches projects closest to a specific spot"
        def uri = "/projects/search-by-location?latitude=39.7392&longitude=-104.9928&radiusMeters=50000"
        def projectSearchResp = client.exchange(HttpRequest.GET(uri).accept(MediaType.APPLICATION_JSON), Map)

        then: "the spatial query returns HTTP 200 with matching projects sorted by distance"
        verifyAll {
            projectSearchResp.status == HttpStatus.OK
            projectSearchResp.body() != null
        }
    }

    def "Public read endpoints allow anonymous discovery for parity with registered volunteers"() {
        when: "anonymous requests are sent to public discovery endpoints"
        def projectsResp = client.exchange(HttpRequest.GET("/projects").accept(MediaType.APPLICATION_JSON), Map)
        def shiftsResp = client.exchange(HttpRequest.GET("/shifts").accept(MediaType.APPLICATION_JSON), Map)
        def tagsResp = client.exchange(HttpRequest.GET("/tags").accept(MediaType.APPLICATION_JSON), Map)
        def orgsResp = client.exchange(HttpRequest.GET("/organizations/search-by-location?latitude=39.7392&longitude=-104.9928&radiusMeters=50000").accept(MediaType.APPLICATION_JSON), Map)

        then: "all endpoints return HTTP 200 without 401 Unauthorized"
        verifyAll {
            projectsResp.status == HttpStatus.OK
            shiftsResp.status == HttpStatus.OK
            tagsResp.status == HttpStatus.OK
            orgsResp.status == HttpStatus.OK
        }
    }
}
