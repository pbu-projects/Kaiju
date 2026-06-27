package lol.pbu.kaiju.core.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.TypeDef;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lol.pbu.kaiju.core.model.JtsPolygonConverter;

import java.util.UUID;

import static io.micronaut.data.model.DataType.OBJECT;

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
        org.locationtech.jts.geom.Geometry geom
) {
    /**
     * Instead of using setters, this method gives the opportunity to take an existing administrative region ID and assign that to
     * the administrative region properties in this administrative region record.
     *
     * @param newId The UUID to assign to the administrative region.
     * @return A new {@link AdministrativeRegion} with the given ID.
     */
    public AdministrativeRegion withId(@NotNull UUID newId) {
        return new AdministrativeRegion(
                newId,
                this.name(),
                this.parentRegionId(),
                this.geom()
        );
    }
}
