package lol.pbu.kaiju.core.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.Relation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

import static io.micronaut.data.annotation.Relation.Kind.ONE_TO_MANY;

@MappedEntity("organizations")
public record Organization(
        @Id @GeneratedValue UUID id,

        @NotBlank(message = "An organization name is required")
        @Size(min = 1, max = 120, message = "Organization name must be between 1 and 120 characters")
        String name,

        @Relation(value = ONE_TO_MANY, mappedBy = "organization")
        List<OrganizationLocation> organizationLocations
) {}
