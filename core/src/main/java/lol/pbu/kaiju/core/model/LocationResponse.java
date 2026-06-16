package lol.pbu.kaiju.core.model;

import io.micronaut.serde.annotation.Serdeable;
import org.locationtech.jts.geom.Point;

import java.util.UUID;

@Serdeable
public record LocationResponse(UUID id, String name, String addressLine, String city, String state, String zipCode,
                               Point geom) {
}