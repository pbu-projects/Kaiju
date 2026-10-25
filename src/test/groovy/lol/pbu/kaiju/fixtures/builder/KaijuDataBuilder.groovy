package lol.pbu.kaiju.fixtures.builder

import jakarta.inject.Inject
import jakarta.inject.Singleton
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource
import lol.pbu.kaiju.domain.AdministrativeRegion
import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.repository.AdministrativeRegionRepository
import lol.pbu.kaiju.repository.BoundaryRepository
import lol.pbu.kaiju.repository.LocationRepository
import lol.pbu.kaiju.repository.OrganizationRepository
import lol.pbu.kaiju.repository.OrganizationUserRepository
import lol.pbu.kaiju.repository.RegionUserRepository
import lol.pbu.kaiju.repository.TagRepository
import lol.pbu.kaiju.repository.UserRepository
import net.datafaker.Faker
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.PrecisionModel

import javax.sql.DataSource
import java.util.UUID

@Singleton
class KaijuDataBuilder {

    private final UserRepository userRepository
    private final AdministrativeRegionRepository regionRepository
    private final LocationRepository locationRepository
    private final TagRepository tagRepository
    private final BoundaryRepository boundaryRepository
    private final OrganizationRepository organizationRepository
    private final OrganizationUserRepository organizationUserRepository
    private final RegionUserRepository regionUserRepository
    private final DataSource dataSource
    private final GeometryFactory geometryFactory
    private final Faker faker

    KaijuDataBuilder() {
        this(null, null, null, null, null, null, null, null, null)
    }

    @Inject
    KaijuDataBuilder(
            UserRepository userRepository,
            AdministrativeRegionRepository regionRepository,
            LocationRepository locationRepository,
            TagRepository tagRepository,
            BoundaryRepository boundaryRepository,
            OrganizationRepository organizationRepository,
            OrganizationUserRepository organizationUserRepository,
            RegionUserRepository regionUserRepository,
            DataSource dataSource
    ) {
        this.userRepository = userRepository
        this.regionRepository = regionRepository
        this.locationRepository = locationRepository
        this.tagRepository = tagRepository
        this.boundaryRepository = boundaryRepository
        this.organizationRepository = organizationRepository
        this.organizationUserRepository = organizationUserRepository
        this.regionUserRepository = regionUserRepository
        this.dataSource = dataSource
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), 4326)
        this.faker = new Faker()
    }

    UserBuilder user() {
        return new UserBuilder(userRepository, faker)
    }

    RegionBuilder region() {
        return new RegionBuilder(regionRepository, geometryFactory, faker)
    }

    LocationBuilder location() {
        return new LocationBuilder(locationRepository, geometryFactory, faker)
    }

    TagBuilder tag() {
        return new TagBuilder(tagRepository)
    }

    BoundaryBuilder boundary() {
        return new BoundaryBuilder(boundaryRepository, geometryFactory)
    }

    OrganizationBuilder organization() {
        return new OrganizationBuilder(organizationRepository, this, faker)
    }

    OrganizationUserBuilder organizationUser() {
        return new OrganizationUserBuilder(organizationUserRepository)
    }

    RegionUserBuilder regionUser() {
        return new RegionUserBuilder(regionUserRepository)
    }

    void linkOrganizationToRegion(UUID orgId, UUID regionId) {
        assert dataSource != null : "Cannot link organization to region without a DataSource"
        String sql = "INSERT INTO organization_regions (organization_id, region_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
        rawDataSource.connection.withCloseable { conn ->
            conn.prepareStatement(sql).withCloseable { stmt ->
                stmt.setObject(1, orgId)
                stmt.setObject(2, regionId)
                stmt.executeUpdate()
            }
        }
    }

    void linkOrganizationToRegion(Organization org, AdministrativeRegion region) {
        linkOrganizationToRegion(org?.id(), region?.id())
    }

    void linkOrganizationToLocation(UUID orgId, UUID locationId) {
        assert dataSource != null : "Cannot link organization to location without a DataSource"
        String sql = "INSERT INTO organization_locations (organization_id, location_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
        rawDataSource.connection.withCloseable { conn ->
            conn.prepareStatement(sql).withCloseable { stmt ->
                stmt.setObject(1, orgId)
                stmt.setObject(2, locationId)
                stmt.executeUpdate()
            }
        }
    }

    void linkOrganizationToLocation(Organization org, Location location) {
        linkOrganizationToLocation(org?.id(), location?.id())
    }

    private DataSource getRawDataSource() {
        return DelegatingDataSource.unwrapDataSource(dataSource)
    }

    GeometryFactory getGeometryFactory() {
        return geometryFactory
    }

    Faker getFaker() {
        return faker
    }
}
