package lol.pbu.kaiju.model;

import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

@Serdeable
public record FeaturedProjectView(
        UUID id,
        String title,
        String description,
        String organizationName,
        String projectTypeLabel,
        String locationLabel,
        boolean verified
) {
}
