package lol.pbu.kaiju.util;

import io.micronaut.core.annotation.Nullable;
import lol.pbu.kaiju.domain.Location;
import lol.pbu.kaiju.repository.LocationRepository;
import org.locationtech.jts.geom.Point;

import java.util.List;

/**
 * Resolves location timezones from map boundary intersections with an optional fallback.
 */
public final class LocationTimezones {

    private LocationTimezones() {
    }

    @Nullable
    public static String resolveTimezone(
            LocationRepository locationRepository,
            @Nullable Point point,
            @Nullable String fallbackTimezone
    ) {
        if (point == null) {
            return fallbackTimezone;
        }
        return locationRepository.findTimezoneByPoint(point)
                .orElse(fallbackTimezone);
    }

    public static Location withResolvedTimezone(LocationRepository locationRepository, Location location) {
        return location.withTimezone(resolveTimezone(locationRepository, location.geom(), location.timezone()));
    }

    public static List<Location> resolveNewLocations(LocationRepository locationRepository, List<Location> locations) {
        return locations.stream()
                .map(loc -> loc.id() == null ? withResolvedTimezone(locationRepository, loc) : loc)
                .toList();
    }
}
