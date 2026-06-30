package lol.pbu.kaiju.core.domain;

import io.micronaut.data.annotation.EmbeddedId;
import io.micronaut.data.annotation.MappedEntity;
import jakarta.validation.constraints.NotNull;
import lol.pbu.kaiju.core.model.RegionUserRole;

@MappedEntity("region_users")
public record RegionUser(
        @EmbeddedId
        RegionUserId id,

        @NotNull(message = "Region user role is required.")
        RegionUserRole role
) {
    /**
     * Instead of using setters, this method gives the opportunity to take an existing composite ID and assign that to
     * the properties in this record.
     *
     * @param newId The composite ID to assign.
     * @return A new {@link RegionUser} with the given ID.
     */
    public RegionUser withId(@NotNull RegionUserId newId) {
        return new RegionUser(newId, this.role());
    }
}
