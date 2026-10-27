package lol.pbu.kaiju.fixtures.builder

import lol.pbu.kaiju.domain.Location
import lol.pbu.kaiju.domain.Organization
import lol.pbu.kaiju.model.VerificationStatus
import lol.pbu.kaiju.repository.OrganizationRepository
import net.datafaker.Faker

import java.time.OffsetDateTime
import java.util.UUID

class OrganizationBuilder {

    private final OrganizationRepository organizationRepository
    private final KaijuDataBuilder dataBuilder
    private final Faker faker

    private UUID id
    private String name
    private String websiteUrl = "https://example.org"
    private UUID parentId
    private Boolean isPublic = true
    private VerificationStatus verificationStatus = VerificationStatus.VERIFIED
    private OffsetDateTime verificationExpiresAt
    private final List<Location> locations = []

    OrganizationBuilder(
            OrganizationRepository organizationRepository,
            KaijuDataBuilder dataBuilder,
            Faker faker
    ) {
        this.organizationRepository = organizationRepository
        this.dataBuilder = dataBuilder
        this.faker = faker
    }

    OrganizationBuilder withId(UUID id) {
        this.id = id
        return this
    }

    OrganizationBuilder withName(String name) {
        this.name = name
        return this
    }

    OrganizationBuilder withWebsiteUrl(String websiteUrl) {
        this.websiteUrl = websiteUrl
        return this
    }

    OrganizationBuilder withParent(UUID parentId) {
        this.parentId = parentId
        return this
    }

    OrganizationBuilder withParent(Organization parent) {
        this.parentId = parent?.id()
        return this
    }

    OrganizationBuilder asPublic() {
        this.isPublic = true
        return this
    }

    OrganizationBuilder asPrivate() {
        this.isPublic = false
        return this
    }

    OrganizationBuilder withPublic(Boolean isPublic) {
        this.isPublic = isPublic
        return this
    }

    OrganizationBuilder verified() {
        this.verificationStatus = VerificationStatus.VERIFIED
        return this
    }

    OrganizationBuilder unverified() {
        this.verificationStatus = VerificationStatus.UNVERIFIED
        return this
    }

    OrganizationBuilder withVerificationStatus(VerificationStatus verificationStatus) {
        this.verificationStatus = verificationStatus
        return this
    }

    OrganizationBuilder withVerificationExpiresAt(OffsetDateTime verificationExpiresAt) {
        this.verificationExpiresAt = verificationExpiresAt
        return this
    }

    OrganizationBuilder withLocation(Location location) {
        if (location != null) {
            this.locations.add(location)
        }
        return this
    }

    OrganizationBuilder withLocation(UUID locationId) {
        if (locationId != null) {
            this.locations.add(new Location(locationId, null, null, null, null, null, null, null, null))
        }
        return this
    }

    Organization build() {
        String orgName = name ?: "Org ${faker.company().name()} ${UUID.randomUUID().toString().substring(0, 8)}"
        return new Organization(
                id,
                orgName,
                websiteUrl,
                parentId,
                isPublic,
                verificationStatus,
                verificationExpiresAt,
                new ArrayList<>(locations)
        )
    }

    Organization persist() {
        assert organizationRepository != null : "Cannot persist organization without an OrganizationRepository"
        Organization orgToSave = new Organization(
                id,
                name ?: "Org ${faker.company().name()} ${UUID.randomUUID().toString().substring(0, 8)}",
                websiteUrl,
                parentId,
                isPublic,
                verificationStatus,
                verificationExpiresAt,
                []
        )
        Organization saved = organizationRepository.save(orgToSave)
        dataBuilder?.trackOrganization(saved.id())
        if (!locations.isEmpty()) {
            assert dataBuilder != null : "Cannot persist organization locations without a KaijuDataBuilder instance"
            locations.each { loc ->
                dataBuilder.linkOrganizationToLocation(saved.id(), loc.id())
            }
        }
        return new Organization(
                saved.id(),
                saved.name(),
                saved.websiteUrl(),
                saved.parentId(),
                saved.isPublic(),
                saved.verificationStatus(),
                saved.verificationExpiresAt(),
                new ArrayList<>(locations)
        )
    }
}
