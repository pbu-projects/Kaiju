package lol.pbu.kaiju.security

import lol.pbu.kaiju.model.UserRole
import spock.lang.Specification
import spock.lang.Unroll

class IdpRoleMapperSpec extends Specification {

    IdpRoleMapper mapper = new IdpRoleMapper()

    def "should return STANDARD_USER when groups collection is null or empty"() {
        expect:
        verifyAll {
            mapper.resolveRoleFromGroups(null) == UserRole.STANDARD_USER
            mapper.resolveRoleFromGroups([]) == UserRole.STANDARD_USER
        }
    }

    def "should return STANDARD_USER when collection contains only null or unmapped elements"() {
        expect:
        mapper.resolveRoleFromGroups([null, "", "Unknown Group"]) == UserRole.STANDARD_USER
    }

    @Unroll
    def "should map single group '#groupName' to role '#expectedRole'"() {
        expect:
        mapper.resolveRoleFromGroups([groupName]) == expectedRole

        where:
        groupName                                 | expectedRole
        IdpRoleMapper.GROUP_GLOBAL_ADMINS         | UserRole.GLOBAL_ADMIN
        IdpRoleMapper.GROUP_REGION_DIRECTORS      | UserRole.REGION_DIRECTOR
        IdpRoleMapper.GROUP_REGION_AGENTS         | UserRole.REGION_AGENT
        "Kaiju Org Admins"                        | UserRole.STANDARD_USER
        "Kaiju Org Managers"                      | UserRole.STANDARD_USER
        "Kaiju Standard Users"                    | UserRole.STANDARD_USER
        "Arbitrary Group"                         | UserRole.STANDARD_USER
    }

    @Unroll
    def "should resolve highest privilege role from multi-group combination #groups to #expectedRole"() {
        expect:
        mapper.resolveRoleFromGroups(groups) == expectedRole

        where:
        groups                                                                                            | expectedRole
        ["Kaiju Standard Users", IdpRoleMapper.GROUP_REGION_AGENTS]                                      | UserRole.REGION_AGENT
        ["Kaiju Standard Users", IdpRoleMapper.GROUP_REGION_DIRECTORS]                                   | UserRole.REGION_DIRECTOR
        [IdpRoleMapper.GROUP_REGION_AGENTS, IdpRoleMapper.GROUP_REGION_DIRECTORS]                         | UserRole.REGION_DIRECTOR
        ["Kaiju Standard Users", IdpRoleMapper.GROUP_GLOBAL_ADMINS]                                       | UserRole.GLOBAL_ADMIN
        [IdpRoleMapper.GROUP_REGION_AGENTS, IdpRoleMapper.GROUP_GLOBAL_ADMINS]                            | UserRole.GLOBAL_ADMIN
        [IdpRoleMapper.GROUP_REGION_DIRECTORS, IdpRoleMapper.GROUP_GLOBAL_ADMINS]                         | UserRole.GLOBAL_ADMIN
        [IdpRoleMapper.GROUP_REGION_AGENTS, IdpRoleMapper.GROUP_REGION_DIRECTORS, IdpRoleMapper.GROUP_GLOBAL_ADMINS] | UserRole.GLOBAL_ADMIN
    }

    def "should resolve role from claims map"() {
        expect:
        verifyAll {
            mapper.resolveRoleFromClaims(null) == UserRole.STANDARD_USER
            mapper.resolveRoleFromClaims([:]) == UserRole.STANDARD_USER
            mapper.resolveRoleFromClaims([(IdpRoleMapper.CLAIMS_GROUPS_KEY): [IdpRoleMapper.GROUP_GLOBAL_ADMINS]]) == UserRole.GLOBAL_ADMIN
            mapper.resolveRoleFromClaims([(IdpRoleMapper.CLAIMS_GROUPS_KEY): [IdpRoleMapper.GROUP_REGION_DIRECTORS]]) == UserRole.REGION_DIRECTOR
            mapper.resolveRoleFromClaims([(IdpRoleMapper.CLAIMS_GROUPS_KEY): "Kaiju Standard Users"]) == UserRole.STANDARD_USER
        }
    }

    def "should handle various groups object types gracefully"() {
        expect:
        verifyAll {
            mapper.resolveRoleFromGroupObject(null) == UserRole.STANDARD_USER
            mapper.resolveRoleFromGroupObject(IdpRoleMapper.GROUP_GLOBAL_ADMINS) == UserRole.GLOBAL_ADMIN
            mapper.resolveRoleFromGroupObject([IdpRoleMapper.GROUP_REGION_DIRECTORS] as String[]) == UserRole.REGION_DIRECTOR
            mapper.resolveRoleFromGroupObject([IdpRoleMapper.GROUP_REGION_AGENTS] as Object[]) == UserRole.REGION_AGENT
            mapper.resolveRoleFromGroupObject(12345) == UserRole.STANDARD_USER
            mapper.resolveRoleFromGroupObject(true) == UserRole.STANDARD_USER
            mapper.resolveRoleFromGroupObject([null, 999]) == UserRole.STANDARD_USER
        }
    }
}
