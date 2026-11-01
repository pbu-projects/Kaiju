package lol.pbu.kaiju.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.annotation.TypeDef;
import io.micronaut.data.model.DataType;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;
import lol.pbu.kaiju.model.RegionAuditAction;

import java.time.OffsetDateTime;
import java.util.UUID;

import static io.micronaut.data.annotation.Relation.Kind.MANY_TO_ONE;

@Serdeable
@MappedEntity(value = "administrative_region_audit_logs", alias = "aral")
public record AdministrativeRegionAuditLog(
        @Id
        @GeneratedValue
        UUID id,

        @NotNull(message = "Audit log region ID is required.")
        UUID regionId,

        @Relation(MANY_TO_ONE)
        @MappedProperty("actor_id")
        @NotNull(message = "Audit log actor is required.")
        User actor,

        @NotNull(message = "Audit log action is required.")
        RegionAuditAction action,

        @Nullable
        @TypeDef(type = DataType.JSON)
        String metadata,

        OffsetDateTime createdAt
) {
    public AdministrativeRegionAuditLog withId(@NotNull UUID newId) {
        return new AdministrativeRegionAuditLog(
                newId,
                this.regionId(),
                this.actor(),
                this.action(),
                this.metadata(),
                this.createdAt()
        );
    }
}
