package lol.pbu.kaiju.fixtures.builder

import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.domain.OrganizationUser
import lol.pbu.kaiju.domain.OrganizationUserId
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.fixtures.TestPersonas
import lol.pbu.kaiju.model.OrganizationUserRole
import lol.pbu.kaiju.repository.OrganizationUserRepository

import java.util.UUID

class OrganizationUserBuilder {

    private final OrganizationUserRepository organizationUserRepository
    private final KaijuDataBuilder dataBuilder

    private UUID userId
    private UUID organizationId
    private OrganizationUserRole role = OrganizationUserRole.ORG_MEMBER

    OrganizationUserBuilder(OrganizationUserRepository organizationUserRepository, KaijuDataBuilder dataBuilder = null) {
        this.organizationUserRepository = organizationUserRepository
        this.dataBuilder = dataBuilder
    }

    OrganizationUserBuilder asOrgAdmin(Object user = null, Object org = null) {
        this.role = OrganizationUserRole.ORG_ADMIN
        if (user != null) {
            withUser(user)
        }
        if (org != null) {
            withOrganization(org)
        }
        return this
    }

    OrganizationUserBuilder asOrgManager(Object user = null, Object org = null) {
        this.role = OrganizationUserRole.ORG_MANAGER
        if (user != null) {
            withUser(user)
        }
        if (org != null) {
            withOrganization(org)
        }
        return this
    }

    OrganizationUserBuilder asOrgMember(Object user = null, Object org = null) {
        this.role = OrganizationUserRole.ORG_MEMBER
        if (user != null) {
            withUser(user)
        }
        if (org != null) {
            withOrganization(org)
        }
        return this
    }

    OrganizationUserBuilder withRole(OrganizationUserRole role) {
        this.role = role
        return this
    }

    OrganizationUserBuilder withUser(UUID userId) {
        this.userId = userId
        return this
    }

    OrganizationUserBuilder withUser(User user) {
        this.userId = user?.id()
        return this
    }

    OrganizationUserBuilder withUser(TestPersonas.Persona persona) {
        this.userId = persona?.id
        return this
    }

    OrganizationUserBuilder withUser(Object user) {
        if (user instanceof UUID) {
            this.userId = user
        } else if (user instanceof User) {
            this.userId = user.id()
        } else if (user instanceof TestPersonas.Persona) {
            this.userId = user.id
        } else if (user != null) {
            this.userId = UUID.fromString(user.toString())
        }
        return this
    }

    OrganizationUserBuilder withOrganization(UUID organizationId) {
        this.organizationId = organizationId
        return this
    }

    OrganizationUserBuilder withOrganization(Organization organization) {
        this.organizationId = organization?.id()
        return this
    }

    OrganizationUserBuilder withOrganization(Object organization) {
        if (organization instanceof UUID) {
            this.organizationId = organization
        } else if (organization instanceof Organization) {
            this.organizationId = organization.id()
        } else if (organization != null) {
            this.organizationId = UUID.fromString(organization.toString())
        }
        return this
    }

    OrganizationUser build() {
        assert userId != null : "userId is required to build OrganizationUser"
        assert organizationId != null : "organizationId is required to build OrganizationUser"
        return new OrganizationUser(new OrganizationUserId(userId, organizationId), role)
    }

    OrganizationUser persist() {
        assert organizationUserRepository != null : "Cannot persist organization user without an OrganizationUserRepository"
        OrganizationUser saved = organizationUserRepository.save(build())
        dataBuilder?.trackOrganizationUser(saved.id().userId(), saved.id().organizationId())
        return saved
    }
}
