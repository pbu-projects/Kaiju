package lol.pbu.kaiju.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.TypeDef;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lol.pbu.kaiju.model.JtsPolygonConverter;
import org.locationtech.jts.geom.Geometry;

import java.util.UUID;

import static io.micronaut.data.model.DataType.OBJECT;

@Serdeable
@MappedEntity("administrative_regions")
public record AdministrativeRegion(
        @Id
        @GeneratedValue
        UUID id,

        @NotBlank(message = "Administrative region name is required.")
        @Size(min = 1, max = 255, message = "Administrative region name must be between 1 and 255 characters.")
        String name,

        @Nullable
        UUID parentRegionId,

        @NotNull(message = "Administrative region geometry is required.")
        @TypeDef(type = OBJECT, converter = JtsPolygonConverter.class)
        Geometry geom,

        @Nullable
        Integer level,

        @Nullable
        String countryCode,

        @Nullable
        String stateCode,

        @Nullable
        String timeZone,

        @Nullable
        String geoid
) {
    public AdministrativeRegion(UUID id, String name, UUID parentRegionId, Geometry geom) {
        this(id, name, parentRegionId, geom, null, null, null, null, null);
    }

    @TypeDef(type = OBJECT, converter = JtsPolygonConverter.class)
    @Override
    public Geometry geom() {
        return geom;
    }

    public AdministrativeRegion withId(@NotNull UUID newId) {
        return new AdministrativeRegion(
                newId,
                this.name(),
                this.parentRegionId(),
                this.geom(),
                this.level(),
                this.countryCode(),
                this.stateCode(),
                this.timeZone(),
                this.geoid()
        );
    }
}
