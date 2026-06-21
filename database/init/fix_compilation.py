import os

domain_path = '/home/jimmy/git/pbu/kaiju/core/src/main/java/lol/pbu/kaiju/core/domain'
model_path = '/home/jimmy/git/pbu/kaiju/core/src/main/java/lol/pbu/kaiju/core/model'

def write_file(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        f.write(content)

# Fix JtsPolygonConverter
write_file(f"{model_path}/JtsPolygonConverter.java", """package lol.pbu.kaiju.core.model;

import io.micronaut.core.convert.ConversionContext;
import io.micronaut.data.model.runtime.convert.AttributeConverter;
import jakarta.inject.Singleton;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.postgresql.util.PGobject;

import java.sql.SQLException;

@Singleton
public class JtsPolygonConverter implements AttributeConverter<Polygon, PGobject> {

    @Override
    public PGobject convertToPersistedValue(Polygon entityValue, ConversionContext context) {
        if (entityValue == null) {
            return null;
        }
        try {
            WKBWriter writer = new WKBWriter();
            byte[] wkb = writer.write(entityValue);
            String hexWkb = WKBWriter.toHex(wkb);

            PGobject pgObject = new PGobject();
            pgObject.setType("geography");
            pgObject.setValue(hexWkb);
            return pgObject;
        } catch (SQLException e) {
            return null;
        }
    }

    @Override
    public Polygon convertToEntityValue(PGobject persistedValue, ConversionContext context) {
        if (persistedValue == null || persistedValue.getValue() == null) {
            return null;
        }
        try {
            WKBReader reader = new WKBReader();
            byte[] bytes = WKBReader.hexToBytes(persistedValue.getValue());
            Polygon polygon = (Polygon) reader.read(bytes);
            if (polygon.getSRID() == 0) {
                polygon.setSRID(4326);
            }
            return polygon;
        } catch (ParseException e) {
            return null;
        }
    }
}
""")

# Add compatibility constructors to Location
write_file(f"{domain_path}/Location.java", """package lol.pbu.kaiju.core.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.annotation.TypeDef;
import io.micronaut.data.model.DataType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lol.pbu.kaiju.core.model.JtsPointConverter;
import org.locationtech.jts.geom.Point;

import java.util.List;
import java.util.UUID;

@MappedEntity("locations")
public record Location(
        @Id
        @GeneratedValue
        UUID id,

        @NotBlank
        String name,

        @NotBlank
        String addressLine,

        @NotBlank
        String city,

        @Nullable
        String stateProvince,

        @Nullable
        String postalCode,

        @NotBlank
        @Size(min = 2, max = 2)
        String countryCode,

        @TypeDef(type = DataType.OBJECT, converter = JtsPointConverter.class)
        Point geom,

        @Relation(value = Relation.Kind.ONE_TO_MANY, mappedBy = "id.locationId")
        List<OrganizationLocation> organizationLocations
) {
    // Compatibility constructor
    public Location(UUID id, String name, String addressLine, String city, String stateProvince, String postalCode, String countryCode, Point geom) {
        this(id, name, addressLine, city, stateProvince, postalCode, countryCode, geom, List.of());
    }
}
""")

# Add compatibility constructors to Project
write_file(f"{domain_path}/Project.java", """package lol.pbu.kaiju.core.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.annotation.sql.JoinTable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lol.pbu.kaiju.core.model.ProjectStatus;
import lol.pbu.kaiju.core.model.ProjectType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@MappedEntity("projects")
public record Project(
        @Id @GeneratedValue UUID id,
        
        @Relation(Relation.Kind.MANY_TO_ONE)
        @NotNull Organization organization,
        
        @NotBlank String title,
        @NotBlank String description,
        @NotNull ProjectType projectType,
        @NotNull ProjectStatus status,
        OffsetDateTime createdAt,
        @Nullable OffsetDateTime deletedAt,
        
        @Relation(Relation.Kind.MANY_TO_ONE)
        @Nullable User deletedBy,

        @Relation(value = Relation.Kind.MANY_TO_MANY)
        @JoinTable(name = "project_locations")
        List<Location> locations,

        @Relation(value = Relation.Kind.MANY_TO_MANY)
        @JoinTable(name = "project_boundaries")
        List<Boundary> boundaries
) {
    // Compatibility constructor (partial)
    public Project(UUID id, String title, String description, ProjectStatus status, List<Location> locations) {
        this(id, null, title, description, ProjectType.STANDARD, status, OffsetDateTime.now(), null, null, locations, List.of());
    }
}
""")

# Add compatibility constructors and methods to Shift
write_file(f"{domain_path}/Shift.java", """package lol.pbu.kaiju.core.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.annotation.sql.JoinTable;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@MappedEntity("shifts")
public record Shift(
        @Id @GeneratedValue UUID id,
        
        @Relation(Relation.Kind.MANY_TO_ONE)
        @NotNull Project project,
        
        boolean isVirtual,
        
        @Relation(Relation.Kind.MANY_TO_ONE)
        @Nullable Location location,
        
        @NotNull OffsetDateTime startTime,
        @NotNull OffsetDateTime endTime,

        @Relation(value = Relation.Kind.MANY_TO_MANY)
        @JoinTable(name = "shift_tags")
        List<Tag> tags
) {
    // Compatibility constructor
    public Shift(UUID id, UUID locationId, String name, OffsetDateTime startTime, OffsetDateTime endTime) {
        this(id, null, false, null, startTime, endTime, List.of());
    }

    // Compatibility methods
    public UUID locationId() {
        return location != null ? location.id() : null;
    }
    
    public String name() {
        return project != null ? project.title() : "Unnamed Shift";
    }
}
""")
