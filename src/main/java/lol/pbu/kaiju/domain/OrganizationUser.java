package lol.pbu.kaiju.domain;

import io.micronaut.data.annotation.EmbeddedId;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;
import lol.pbu.kaiju.model.OrganizationUserRole;

@Serdeable
@MappedEntity("organization_users")
public record OrganizationUser(
        @EmbeddedId
        OrganizationUserId id,

        @NotNull(message = "Organization user role is required.")
        OrganizationUserRole role
) {
    /**
     * Instead of using setters, this method gives the opportunity to take an existing composite ID and assign that to
     * the properties in this record.
     *
     * @param newId The composite ID to assign.
     * @return A new {@link OrganizationUser} with the given ID.
     */
    public OrganizationUser withId(@NotNull OrganizationUserId newId) {
        return new OrganizationUser(newId, this.role());
    }
}
