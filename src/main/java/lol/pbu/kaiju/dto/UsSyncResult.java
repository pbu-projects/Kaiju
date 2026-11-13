package lol.pbu.kaiju.dto;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/**
 * Summary of a US Census TIGERweb boundary sync run for a state or country level.
 */
@Serdeable
public record UsSyncResult(
        String stateCode,
        int level,
        int created,
        int updated,
        int skipped,
        int failed,
        List<String> errors
) {
    public UsSyncResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
