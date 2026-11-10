package lol.pbu.kaiju.controller

import geb.spock.GebSpec
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.openqa.selenium.JavascriptExecutor
import spock.lang.Stepwise

@MicronautTest(transactional = false)
@Stepwise
class JourneyHarnessGebSpec extends GebSpec {

    @Inject
    EmbeddedServer embeddedServer

    def setup() {
        browser.baseUrl = embeddedServer.URI.toString()
    }

    def cleanupSpec() {
        browser.quit()
    }

    private void clickElement(navigator) {
        JavascriptExecutor js = (JavascriptExecutor) browser.driver
        js.executeScript("arguments[0].scrollIntoView({block: 'center'}); arguments[0].click();", navigator.firstElement())
    }

    def "Anonymous visitor searches for active volunteer projects near Denver (CUR-001)"() {
        when: "an anonymous visitor opens the journey harness"
        go("/harness")

        then: "the visitor lands on the page unauthenticated and initial projects load"
        waitFor(10) { $("#active-auth-badge").text().contains("Anonymous Visitor") }
        waitFor(10) { $("#j1-list-container .project-card").size() > 0 }

        when: "the visitor enters a Denver location and runs a spatial search"
        $("#j1-city-zip").value("Denver, CO 80265")
        clickElement($("#btn-j1-spatial-search"))

        then: "project cards near Denver appear in the search results list"
        waitFor(10) { $("#j1-list-container .project-card").size() > 0 }
        def cards = $("#j1-list-container .project-card")

        verifyAll {
            $("#active-auth-badge").text().contains("Anonymous Visitor")
            cards.size() >= 1
            $("#j1-results-count").text().contains("project(s)")
            $("#j1-discovery-banner").text().contains("HTTP 200")
            cards.first().find("h4").text().trim().length() > 0
        }
    }

    def "Anonymous visitor toggles between list view and map view (CUR-002)"() {
        when: "the visitor switches to map view"
        clickElement($("#btn-view-map"))

        then: "the map container displays and plots SVG pin markers"
        waitFor(10) { !$("#j1-map-container").hasClass("hidden") }
        waitFor(10) { $("#j1-map-svg circle").size() > 0 }

        verifyAll {
            $("#j1-list-container").hasClass("hidden")
            !$("#j1-map-container").hasClass("hidden")
            $("#j1-map-svg circle").size() >= 1
            $("#j1-map-legend").text().contains("Plotting")
        }

        when: "the visitor switches back to list view"
        clickElement($("#btn-view-list"))

        then: "the list container displays project cards again"
        waitFor(10) { !$("#j1-list-container").hasClass("hidden") }

        verifyAll {
            !$("#j1-list-container").hasClass("hidden")
            $("#j1-map-container").hasClass("hidden")
            $("#j1-list-container .project-card").size() >= 1
        }
    }

    def "Anonymous visitor opens a project card to view details and shift times (CUR-010, CUR-012)"() {
        when: "the visitor clicks View Project & Shifts on the first project card"
        clickElement($("#j1-list-container .project-card button", 0))

        then: "the project detail section populates with title, description, and shifts"
        waitFor(10) { $("#j1-project-detail-view h4").text().trim().length() > 0 }
        waitFor(10) { $("#j1-shift-select option").size() > 0 }

        verifyAll {
            $("#j1-project-detail-view h4").text().trim().length() > 0
            $("#j1-project-detail-view p").text().trim().length() > 0
            $("#j1-project-detail-view").text().contains("Sponsoring Org:")
            $("#j1-shift-select option").size() >= 1
        }
    }

    def "Anonymous visitor filters projects by keyword or title (CUR-003)"() {
        when: "the visitor enters a specific project title into the keyword filter and applies filters"
        String targetTitle = "Downtown Denver Mall & Civic Clean-Up"
        $("#j1-title-filter").value(targetTitle)
        clickElement($("#btn-j1-apply-filters"))

        then: "only the matching project appears in the filtered list"
        waitFor(10) { $("#j1-filter-banner").text().contains("HTTP 200") && $("#j1-list-container .project-card").size() == 1 }

        verifyAll {
            $("#j1-list-container .project-card").size() == 1
            $("#j1-list-container .project-card h4", 0).text().trim() == targetTitle
            $("#j1-filter-banner").text().contains("HTTP 200")
        }
    }
}
