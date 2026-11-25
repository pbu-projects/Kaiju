package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;
import lol.pbu.kaiju.model.OrganizationUserRole;

@Serdeable
public record UpdateOrganizationUserRequest(
        @NotNull(message = "Organization user role is required.")
        OrganizationUserRole role
) {}
