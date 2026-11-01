package lol.pbu.kaiju.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.AdministrativeRegion;
import lol.pbu.kaiju.dto.CreateAdministrativeRegionCommand;
import lol.pbu.kaiju.dto.IngestBoundaryCommand;
import lol.pbu.kaiju.dto.IngestBoundaryResponse;
import lol.pbu.kaiju.dto.UpdateAdministrativeRegionCommand;
import lol.pbu.kaiju.service.AdministrativeRegionService;
import lol.pbu.kaiju.service.BoundaryIngestionService;
import lol.pbu.kaiju.util.PageableUtils;
import org.jspecify.annotations.NonNull;

import java.util.Optional;
import java.util.UUID;

import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;
import static lol.pbu.kaiju.security.Permission.REGION_MANAGE_CLAIM;
import static lol.pbu.kaiju.security.Permission.SYSTEM_ADMIN_CLAIM;

/**
 * REST controller for managing geographical administrative regions.
 *
 * Enforces a two-tier security model: read operations require general authentication,
 * while mutating operations are restricted to platform administrators and regional managers.
 */
@ExecuteOn(TaskExecutors.VIRTUAL)
@Secured(IS_AUTHENTICATED)
@Controller("/administrative-regions")
public class AdministrativeRegionController {

    public static final String DEFAULT_SORT_FIELD = "name";

    private final AdministrativeRegionService administrativeRegionService;
    private final BoundaryIngestionService boundaryIngestionService;

    public AdministrativeRegionController(
            AdministrativeRegionService administrativeRegionService,
            BoundaryIngestionService boundaryIngestionService
    ) {
        this.administrativeRegionService = administrativeRegionService;
        this.boundaryIngestionService = boundaryIngestionService;
    }

    /**
     * Retrieves a paginated list of administrative regions using cursor-based pagination.
     * Accessible to all authenticated platform roles.
     *
     * @param pageable cursor pagination parameters (cursor, limit, sort)
     * @return a page containing administrative region entities and next cursor details
     */
    @Get
    public CursoredPage<AdministrativeRegion> getAdministrativeRegions(@Nullable CursoredPageable pageable) {
        return administrativeRegionService.getAdministrativeRegions(PageableUtils.resolvePageable(pageable, DEFAULT_SORT_FIELD));
    }

    /**
     * Retrieves an administrative region by its unique identifier.
     * Accessible to all authenticated platform roles.
     *
     * @param id the unique UUID of the administrative region
     * @return an Optional containing the matching administrative region, or empty if not found
     */
    @Get("/{id}")
    public Optional<AdministrativeRegion> getAdministrativeRegion(@PathVariable UUID id) {
        return administrativeRegionService.getAdministrativeRegionById(id);
    }

    /**
     * Creates and persists a new administrative region polygon.
     * Restricted to platform administrators and regional managers.
     *
     * @param command        the administrative region creation command
     * @param authentication the authenticated user making the request
     * @return the persisted administrative region
     */
    @Secured({SYSTEM_ADMIN_CLAIM, REGION_MANAGE_CLAIM})
    @Post
    public AdministrativeRegion addAdministrativeRegion(
            @Valid @Body CreateAdministrativeRegionCommand command,
            @NonNull Authentication authentication
    ) {
        UUID actorUserId = UUID.fromString(authentication.getName());
        return administrativeRegionService.createAdministrativeRegion(command, actorUserId);
    }

    /**
     * Updates an existing administrative region by ID.
     * Restricted to platform administrators and regional managers.
     *
     * @param id             the unique UUID of the administrative region to update
     * @param command        the updated region command data
     * @param authentication the authenticated user making the request
     * @return the updated administrative region
     */
    @Secured({SYSTEM_ADMIN_CLAIM, REGION_MANAGE_CLAIM})
    @Put("/{id}")
    public AdministrativeRegion updateAdministrativeRegion(
            @PathVariable UUID id,
            @Valid @Body UpdateAdministrativeRegionCommand command,
            @NonNull Authentication authentication
    ) {
        UUID actorUserId = UUID.fromString(authentication.getName());
        return administrativeRegionService.updateAdministrativeRegion(id, command, actorUserId);
    }

    /**
     * Deletes an administrative region by its unique identifier.
     * Restricted to platform administrators and regional managers.
     *
     * @param id             the unique UUID of the administrative region to delete
     * @param authentication the authenticated user making the request
     */
    @Secured({SYSTEM_ADMIN_CLAIM, REGION_MANAGE_CLAIM})
    @Delete("/{id}")
    public void deleteAdministrativeRegion(
            @PathVariable UUID id,
            @NonNull Authentication authentication
    ) {
        UUID actorUserId = UUID.fromString(authentication.getName());
        administrativeRegionService.deleteAdministrativeRegion(id, actorUserId);
    }

    /**
     * Ingests, simplifies, and validates a geographic boundary for an administrative region
     * from an authoritative external source such as US Census Bureau TIGERweb.
     * Restricted strictly to platform administrators.
     *
     * @param id             the unique UUID of the administrative region to update
     * @param command        the boundary ingestion command
     * @param authentication the authenticated user making the request
     * @return the ingestion summary response
     */
    @Secured(SYSTEM_ADMIN_CLAIM)
    @Post("/{id}/boundary/ingest")
    public IngestBoundaryResponse ingestBoundary(
            @PathVariable UUID id,
            @Valid @Body IngestBoundaryCommand command,
            @NonNull Authentication authentication
    ) {
        UUID actorUserId = UUID.fromString(authentication.getName());
        return boundaryIngestionService.ingestBoundary(id, command, actorUserId);
    }
}
