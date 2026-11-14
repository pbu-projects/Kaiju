package lol.pbu.kaiju.service

import spock.lang.Specification
import spock.lang.Unroll

class UsStateCatalogSpec extends Specification {

    def "should list all 56 US states and territories"() {
        when: "listing all entries in the catalog"
        def entries = UsStateCatalog.all()

        then: "all 56 states, DC, and territories are present"
        verifyAll {
            entries.size() == 56
            entries.any { it.stateCode() == "CO" && it.fipsCode() == "08" && !it.cdpOnlyForPlaces() }
            entries.any { it.stateCode() == "HI" && it.fipsCode() == "15" && it.cdpOnlyForPlaces() }
        }
    }

    @Unroll
    def "should look up state by code '#input'"(String input, String expectedFips) {
        when: "looking up by state code"
        def result = UsStateCatalog.findByStateCode(input)

        then: "the expected state is returned when valid"
        if (expectedFips != null) {
            verifyAll(result) {
                isPresent()
                get().fipsCode() == expectedFips
            }
        } else {
            result.isEmpty()
        }

        where:
        input  | expectedFips
        "CO"   | "08"
        " co " | "08"
        "HI"   | "15"
        "ZZ"   | null
        ""     | null
        "   "  | null
        null   | null
    }

    @Unroll
    def "should look up state by FIPS code '#input'"(String input, String expectedStateCode) {
        when: "looking up by FIPS code"
        def result = UsStateCatalog.findByFipsCode(input)

        then: "the expected state is returned when valid"
        if (expectedStateCode != null) {
            verifyAll(result) {
                isPresent()
                get().stateCode() == expectedStateCode
            }
        } else {
            result.isEmpty()
        }

        where:
        input  | expectedStateCode
        "08"   | "CO"
        " 15 " | "HI"
        "99"   | null
        ""     | null
        "   "  | null
        null   | null
    }
}
