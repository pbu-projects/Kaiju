package lol.pbu.kaiju.domain

import jakarta.validation.ValidationException
import spock.lang.Specification
import spock.lang.Unroll

import java.time.OffsetDateTime

import static lol.pbu.kaiju.model.ProjectStatus.DRAFT
import static lol.pbu.kaiju.model.ProjectType.STANDARD
import static lol.pbu.kaiju.model.VerificationStatus.UNVERIFIED

class ShiftSpec extends Specification {

    private static Project createDummyProject() {
        def org = new Organization(UUID.randomUUID(), "Dummy Org", null, null, true, UNVERIFIED, null, [])
        return new Project(UUID.randomUUID(), org, null, "Dummy Project", "Description", STANDARD, DRAFT, OffsetDateTime.now(), null, null, [], [])
    }

    private static Location createDummyLocation() {
        return new Location(UUID.randomUUID(), "Dummy Location", "123 St", "City", null, null, "US", null)
    }

    def "positive testing | constructs a virtual shift without a location"() {
        given:
        def expectedProject = createDummyProject()
        def expectedStart = OffsetDateTime.now()
        def expectedEnd = expectedStart.plusHours(2)

        when: "creating a valid virtual shift"
        def virtualShift = new Shift(UUID.randomUUID(), expectedProject, true, null, expectedStart, expectedEnd, [])

        then: "the virtual shift is constructed without a location"
        verifyAll(virtualShift) {
            isVirtual()
            location() == null
            project() == expectedProject
            startTime() == expectedStart
            endTime() == expectedEnd
        }
    }

    def "positive testing | constructs a physical shift with a location"() {
        given:
        def expectedProject = createDummyProject()
        def expectedLocation = createDummyLocation()
        def expectedStart = OffsetDateTime.now()
        def expectedEnd = expectedStart.plusHours(2)

        when: "creating a valid physical shift"
        def physicalShift = new Shift(UUID.randomUUID(), expectedProject, false, expectedLocation, expectedStart, expectedEnd, [])

        then: "the physical shift is constructed with the location"
        verifyAll(physicalShift) {
            !isVirtual()
            location() == expectedLocation
            project() == expectedProject
            startTime() == expectedStart
            endTime() == expectedEnd
        }
    }

    @Unroll
    def "negative testing | constructor throws ValidationException for invalid configuration: #caseName"(Boolean isVirtual, Location location) {
        given:
        def project = createDummyProject()

        when:
        new Shift(UUID.randomUUID(), project, isVirtual, location, OffsetDateTime.now(), OffsetDateTime.now().plusHours(2), [])

        then:
        def ex = thrown(ValidationException)
        ex.message == "A shift must have a location if it is not virtual, and must not have a location if it is virtual."

        where:
        caseName                            | isVirtual | location
        "virtual shift with a location"     | true      | createDummyLocation()
        "physical shift without a location" | false     | null
    }
}
