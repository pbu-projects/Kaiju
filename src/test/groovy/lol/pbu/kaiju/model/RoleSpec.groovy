package lol.pbu.kaiju.model

import lol.pbu.kaiju.security.Permission
import spock.lang.Specification
import spock.lang.Unroll

class RoleSpec extends Specification {

    @Unroll
    def "UserRole #role.name() hasPermission returns true for each permission it carries"() {
        expect:
        role.getPermissions().every { role.hasPermission(it) }

        where:
        role << UserRole.values()
    }

    def "UserRole.hasPermission returns false for a permission the role does not carry"() {
        expect: "STANDARD_USER does not have SYSTEM_ADMIN"
        !UserRole.STANDARD_USER.hasPermission(Permission.SYSTEM_ADMIN)

        and: "REGION_AGENT does not have SYSTEM_ADMIN"
        !UserRole.REGION_AGENT.hasPermission(Permission.SYSTEM_ADMIN)
    }

    def "ProjectUserRole enum contains PROJECT_SPONSOR"() {
        expect:
        ProjectUserRole.values().contains(ProjectUserRole.PROJECT_SPONSOR)
        ProjectUserRole.valueOf("PROJECT_SPONSOR") == ProjectUserRole.PROJECT_SPONSOR
    }
}
