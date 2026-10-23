package lol.pbu.kaiju.client

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.client.api.DefaultApi
import lol.pbu.kaiju.client.model.AdministrativeRegion
import lol.pbu.kaiju.client.model.CreateTagCommand
import lol.pbu.kaiju.client.model.CursoredPageAdministrativeRegion
import lol.pbu.kaiju.client.model.CursoredPageBoundary
import lol.pbu.kaiju.client.model.CursoredPageLocation
import lol.pbu.kaiju.client.model.CursoredPageOrganization
import lol.pbu.kaiju.client.model.CursoredPageOrganizationAuditLog
import lol.pbu.kaiju.client.model.CursoredPageOrganizationUser
import lol.pbu.kaiju.client.model.CursoredPageProject
import lol.pbu.kaiju.client.model.CursoredPageProjectAuditLog
import lol.pbu.kaiju.client.model.CursoredPageRegionUser
import lol.pbu.kaiju.client.model.CursoredPageShift
import lol.pbu.kaiju.client.model.CursoredPageTag
import lol.pbu.kaiju.client.model.CursoredPageUser
import lol.pbu.kaiju.client.model.Organization
import lol.pbu.kaiju.client.model.OrganizationUser
import lol.pbu.kaiju.client.model.RegionUser
import lol.pbu.kaiju.client.model.RoleUpdateRequest
import lol.pbu.kaiju.client.model.Tag
import lol.pbu.kaiju.client.model.UpdateTagCommand
import lol.pbu.kaiju.client.model.User
import lol.pbu.kaiju.client.model.UserRole
import lol.pbu.kaiju.controller.BaseControllerSpec
import lol.pbu.kaiju.fixtures.TestPersonas

@MicronautTest(transactional = false)
class ApiClientSpec extends BaseControllerSpec {

    @Inject
    DefaultApi defaultApi

    protected void loginAs(TestPersonas.Persona persona) {
        Map<String, Object> claims = [
                "sub"               : persona.id.toString(),
                "email"             : persona.email,
                "preferred_username": persona.displayName,
                "email_verified"    : true,
                "iss"               : TEST_ISSUER,
                "aud"               : TEST_AUDIENCE,
                "roles"             : [persona.systemRole.name()]
        ]
        ClientAuthFilter.token = tokenGenerator.generateToken(claims).orElseThrow()
    }

    def cleanup() {
        ClientAuthFilter.token = null
    }

    def "can get current user profile"() {
        given: "a logged in global admin"
        loginAs(TestPersonas.SARAH_ADMIN)

        when: "calling current user endpoint"
        User user = defaultApi.getCurrentUser()

        then: "current user details match persona"
        verifyAll(user) {
            it != null
            id == TestPersonas.SARAH_ADMIN.id
            email == TestPersonas.SARAH_ADMIN.email
        }
    }

    def "can list and read administrative regions"() {
        given: "a logged in global admin"
        loginAs(TestPersonas.SARAH_ADMIN)

        when: "fetching paginated administrative regions"
        CursoredPageAdministrativeRegion page = defaultApi.getAdministrativeRegions(null)

        then: "regions list is not empty"
        verifyAll(page) {
            it != null
            content != null
            !content.isEmpty()
        }

        when: "fetching a specific seeded region by id"
        AdministrativeRegion region = defaultApi.getAdministrativeRegion(TestPersonas.COLORADO_STATE_REGION_ID)

        then: "the specific region is returned"
        verifyAll(region) {
            it != null
            id == TestPersonas.COLORADO_STATE_REGION_ID
        }
    }

    def "can list and read organizations"() {
        given: "a logged in global admin"
        loginAs(TestPersonas.SARAH_ADMIN)

        when: "fetching paginated organizations"
        CursoredPageOrganization page = defaultApi.getOrganizations(null)

        then: "organizations list is not empty"
        verifyAll(page) {
            it != null
            content != null
            !content.isEmpty()
        }

        when: "fetching a specific seeded organization by id"
        Organization org = defaultApi.getOrganization(TestPersonas.NATIONAL_RELIEF_NETWORK_ID)

        then: "the specific organization is returned"
        verifyAll(org) {
            it != null
            id == TestPersonas.NATIONAL_RELIEF_NETWORK_ID
        }
    }

    def "can list and read users"() {
        given: "a logged in global admin"
        loginAs(TestPersonas.SARAH_ADMIN)

        when: "fetching paginated users"
        CursoredPageUser page = defaultApi.getUsers(null)

        then: "users list is not empty"
        verifyAll(page) {
            it != null
            content != null
            !content.isEmpty()
        }

        when: "fetching a specific user by id"
        User user = defaultApi.getUser(TestPersonas.SARAH_ADMIN.id)

        then: "the user is returned"
        verifyAll(user) {
            it != null
            id == TestPersonas.SARAH_ADMIN.id
        }

        when: "updating user role"
        RoleUpdateRequest roleRequest = new RoleUpdateRequest(UserRole.REGION_AGENT)
        User updatedUser = defaultApi.updateUserRole(TestPersonas.VICTOR_VOLUNTEER.id, roleRequest)

        then: "user role is updated"
        verifyAll(updatedUser) {
            it != null
            role == UserRole.REGION_AGENT
        }
    }

    def "can create update and delete tags"() {
        given: "a logged in global admin"
        loginAs(TestPersonas.SARAH_ADMIN)
        CreateTagCommand createCommand = new CreateTagCommand("client-tag-test")

        when: "creating a new tag"
        Tag createdTag = defaultApi.addTag(createCommand)

        then: "the tag is created with valid id"
        verifyAll(createdTag) {
            it != null
            id != null
            name == "client-tag-test"
        }

        when: "listing tags"
        CursoredPageTag tagsPage = defaultApi.getTags(null)

        then: "the created tag appears in list"
        verifyAll(tagsPage) {
            it != null
            content.any { it.name == "client-tag-test" }
        }

        when: "updating the tag"
        UpdateTagCommand updateCommand = new UpdateTagCommand("client-tag-updated")
        Tag updatedTag = defaultApi.updateTag(createdTag.id, updateCommand)

        then: "the tag name is updated"
        verifyAll(updatedTag) {
            it != null
            name == "client-tag-updated"
        }

        when: "deleting the tag"
        defaultApi.deleteTag(createdTag.id)
        CursoredPageTag tagsAfterDelete = defaultApi.getTags(null)

        then: "the deleted tag is gone"
        verifyAll(tagsAfterDelete) {
            it != null
            !content.any { it.id == createdTag.id }
        }
    }

    def "can list projects and shifts"() {
        given: "a logged in global admin"
        loginAs(TestPersonas.SARAH_ADMIN)

        when: "fetching projects"
        CursoredPageProject projects = defaultApi.getProjects(null, null)

        then: "projects response is valid"
        projects != null

        when: "fetching shifts"
        CursoredPageShift shifts = defaultApi.getShifts(null)

        then: "shifts response is valid"
        shifts != null
    }

    def "can list locations and boundaries"() {
        given: "a logged in global admin"
        loginAs(TestPersonas.SARAH_ADMIN)

        when: "fetching locations"
        CursoredPageLocation locations = defaultApi.getLocations(null)

        then: "locations response is valid"
        locations != null

        when: "fetching boundaries"
        CursoredPageBoundary boundaries = defaultApi.getBoundaries(null)

        then: "boundaries response is valid"
        boundaries != null
    }

    def "can list audit logs"() {
        given: "a logged in global admin"
        loginAs(TestPersonas.SARAH_ADMIN)

        when: "fetching organization audit logs"
        CursoredPageOrganizationAuditLog orgLogs = defaultApi.getOrganizationAuditLogs(null)

        then: "organization audit logs response is valid"
        orgLogs != null

        when: "fetching project audit logs"
        CursoredPageProjectAuditLog projLogs = defaultApi.getProjectAuditLogs(null)

        then: "project audit logs response is valid"
        projLogs != null
    }

    def "can list region users and organization users"() {
        given: "a logged in global admin"
        loginAs(TestPersonas.SARAH_ADMIN)

        when: "fetching region users"
        CursoredPageRegionUser regionUsers = defaultApi.getRegionUsers(null)

        then: "region users response is valid"
        regionUsers != null

        when: "fetching a specific region user"
        RegionUser regionUser = defaultApi.getRegionUser(TestPersonas.RACHEL_STATE_DIRECTOR.id, TestPersonas.COLORADO_STATE_REGION_ID)

        then: "region user details are returned"
        verifyAll(regionUser) {
            it != null
            id.userId == TestPersonas.RACHEL_STATE_DIRECTOR.id
            id.regionId == TestPersonas.COLORADO_STATE_REGION_ID
        }

        when: "fetching organization users"
        CursoredPageOrganizationUser orgUsers = defaultApi.getOrganizationUsers(null)

        then: "organization users response is valid"
        orgUsers != null

        when: "fetching a specific organization user"
        OrganizationUser orgUser = defaultApi.getOrganizationUser(TestPersonas.NATHAN_NATIONAL_ADMIN.id, TestPersonas.NATIONAL_RELIEF_NETWORK_ID)

        then: "organization user details are returned"
        verifyAll(orgUser) {
            it != null
            id.userId == TestPersonas.NATHAN_NATIONAL_ADMIN.id
            id.organizationId == TestPersonas.NATIONAL_RELIEF_NETWORK_ID
        }
    }
}
