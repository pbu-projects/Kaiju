package lol.pbu.kaiju.core.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.Relation;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.UUID;

@MappedEntity("organizations")
public record Organization(
        @Id @GeneratedValue UUID id,
        @NotBlank String name,
        @Nullable String websiteUrl,
        @Nullable UUID parentId,

        @Relation(value = Relation.Kind.ONE_TO_MANY, mappedBy = "id.organizationId")
        List<OrganizationLocation> organizationLocations
) {}
