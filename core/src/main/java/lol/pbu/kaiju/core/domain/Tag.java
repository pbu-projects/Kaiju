package lol.pbu.kaiju.core.domain;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@MappedEntity("tags")
public record Tag(
        @Id @GeneratedValue UUID id,
        @NotBlank @Size(max = 50) String name
) {}
