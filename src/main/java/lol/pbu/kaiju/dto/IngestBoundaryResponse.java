package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

@Serdeable
public record IngestBoundaryResponse(
        UUID regionId,
        String name,
        int originalPoints,
        int simplifiedPoints,
        String reductionRatio,
        boolean valid
) {
}
