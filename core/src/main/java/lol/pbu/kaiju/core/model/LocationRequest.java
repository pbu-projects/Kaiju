package lol.pbu.kaiju.core.model;

import io.micronaut.serde.annotation.Serdeable;
import org.locationtech.jts.geom.Point;

@Serdeable
public record LocationRequest(
    String name,
    String addressLine,
    String city,
    String state,
    String zipCode,
    Point geom
) {}