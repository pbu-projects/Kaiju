package lol.pbu.kaiju.core.domain;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import org.locationtech.jts.geom.Point;

import java.util.UUID;

@MappedEntity("locations")
public record Location(
        @Id
        @GeneratedValue
        UUID id,

        String name,
        String addressLine,
        String city,
        String state,
        String zipCode,

        Point geom
) {}