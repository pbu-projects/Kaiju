package lol.pbu.kaiju.core.domain;

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

@MappedEntity("boundaries")
public record Boundary(
        @Id
        @GeneratedValue
        UUID id,

        @NotBlank(message = "Boundary name is required.")
        @Size(min = 1, max = 255, message = "Boundary name must be between 1 and 255 characters.")
        String name,

        @NotNull(message = "Boundary geometry is required.")
        @TypeDef(type = OBJECT, converter = JtsPolygonConverter.class)
        org.locationtech.jts.geom.Geometry geom
) {
    /**
     * Instead of using setters, this method gives the opportunity to take an existing boundary ID and assign that to
     * the boundary properties in this boundary record.
     *
     * @param newId The UUID to assign to the boundary.
     * @return A new {@link Boundary} with the given ID.
     */
    public Boundary withId(@NotNull UUID newId) {
        return new Boundary(
                newId,
                this.name(),
                this.geom()
        );
    }
}
