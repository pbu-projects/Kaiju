package lol.pbu.kaiju.core.domain;

import io.micronaut.data.annotation.Embeddable;
import io.micronaut.data.annotation.EmbeddedId;
import io.micronaut.data.annotation.MappedEntity;
import jakarta.validation.constraints.NotNull;
import lol.pbu.kaiju.core.model.LocationType;

import java.util.UUID;

@MappedEntity("organization_locations")
public record OrganizationLocation(@EmbeddedId Id id, @NotNull LocationType locationType, boolean isPublic) {
    @Embeddable
    public record Id(@NotNull UUID organizationId, @NotNull UUID locationId) {
    }
}
