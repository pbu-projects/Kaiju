package lol.pbu.kaiju.fixtures.builder

import lol.pbu.kaiju.domain.AdministrativeRegion
import lol.pbu.kaiju.domain.RegionUser
import lol.pbu.kaiju.domain.RegionUserId
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.fixtures.TestPersonas
import lol.pbu.kaiju.model.RegionUserRole
import lol.pbu.kaiju.repository.RegionUserRepository

import java.util.UUID

class RegionUserBuilder {

    private final RegionUserRepository regionUserRepository
    private final KaijuDataBuilder dataBuilder

    private UUID userId
    private UUID regionId
    private RegionUserRole role = RegionUserRole.REGION_AGENT

    RegionUserBuilder(RegionUserRepository regionUserRepository, KaijuDataBuilder dataBuilder = null) {
        this.regionUserRepository = regionUserRepository
        this.dataBuilder = dataBuilder
    }

    RegionUserBuilder asRegionDirector(Object user = null, Object region = null) {
        this.role = RegionUserRole.REGION_DIRECTOR
        if (user != null) {
            withUser(user)
        }
        if (region != null) {
            withRegion(region)
        }
        return this
    }

    RegionUserBuilder asRegionAgent(Object user = null, Object region = null) {
        this.role = RegionUserRole.REGION_AGENT
        if (user != null) {
            withUser(user)
        }
        if (region != null) {
            withRegion(region)
        }
        return this
    }

    RegionUserBuilder withRole(RegionUserRole role) {
        this.role = role
        return this
    }

    RegionUserBuilder withUser(UUID userId) {
        this.userId = userId
        return this
    }

    RegionUserBuilder withUser(User user) {
        this.userId = user?.id()
        return this
    }

    RegionUserBuilder withUser(TestPersonas.Persona persona) {
        this.userId = persona?.id
        return this
    }

    RegionUserBuilder withUser(Object user) {
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

    RegionUserBuilder withRegion(UUID regionId) {
        this.regionId = regionId
        return this
    }

    RegionUserBuilder withRegion(AdministrativeRegion region) {
        this.regionId = region?.id()
        return this
    }

    RegionUserBuilder withRegion(Object region) {
        if (region instanceof UUID) {
            this.regionId = region
        } else if (region instanceof AdministrativeRegion) {
            this.regionId = region.id()
        } else if (region != null) {
            this.regionId = UUID.fromString(region.toString())
        }
        return this
    }

    RegionUser build() {
        assert userId != null : "userId is required to build RegionUser"
        assert regionId != null : "regionId is required to build RegionUser"
        return new RegionUser(new RegionUserId(userId, regionId), role)
    }

    RegionUser persist() {
        assert regionUserRepository != null : "Cannot persist region user without a RegionUserRepository"
        RegionUser saved = regionUserRepository.save(build())
        dataBuilder?.trackRegionUser(saved.id().userId(), saved.id().regionId())
        return saved
    }
}
