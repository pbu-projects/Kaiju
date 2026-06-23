package lol.pbu.kaiju.core.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.annotation.sql.JoinColumn;
import io.micronaut.data.annotation.sql.JoinTable;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.UUID;

import static io.micronaut.data.annotation.Relation.Kind.ONE_TO_MANY;

@MappedEntity("organizations")
public record Organization(
        @Id @GeneratedValue UUID id,
        @NotBlank String name,
        @Nullable String websiteUrl,
        @Nullable UUID parentId,

        @Relation(value = ONE_TO_MANY, mappedBy = "id.organizationId")
        @JoinTable(
                name = "organization_locations",
                joinColumns = @JoinColumn(name = "organization_id"),
                inverseJoinColumns = @JoinColumn(name = "location_id")
        )
        List<Location> locations
) {
}
