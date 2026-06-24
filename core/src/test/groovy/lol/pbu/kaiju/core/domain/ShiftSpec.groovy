package lol.pbu.kaiju.core.domain

import jakarta.validation.ValidationException
import spock.lang.Specification
import spock.lang.Unroll

import java.time.OffsetDateTime

import static lol.pbu.kaiju.core.model.ProjectStatus.DRAFT
import static lol.pbu.kaiju.core.model.ProjectType.STANDARD

class ShiftSpec extends Specification {

    private static Project createDummyProject() {
        def org = new Organization(UUID.randomUUID(), "Dummy Org", null, null, [])
        return new Project(UUID.randomUUID(), org, "Dummy Project", "Description", STANDARD, DRAFT, OffsetDateTime.now(), null, null, [], [])
    }

    private static Location createDummyLocation() {
        return new Location(UUID.randomUUID(), "Dummy Location", "123 St", "City", null, null, "US", null)
    }

    def "positive testing | isValidLocationLogic should return true for valid configurations"() {
        given:
        def project = createDummyProject()
        def location = createDummyLocation()

        when: "creating a valid virtual shift"
        def virtualShift = new Shift(UUID.randomUUID(),
                project,
                true,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now().plusHours(2),
                [])

        then: "isValidLocationLogic returns true"
        virtualShift.isValidLocationLogic()

        when: "creating a valid physical shift"
        def physicalShift = new Shift(UUID.randomUUID(), project, false, location, OffsetDateTime.now(), OffsetDateTime.now().plusHours(2), [])

        then: "isValidLocationLogic returns true"
        physicalShift.isValidLocationLogic()
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
