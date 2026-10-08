package lol.pbu.kaiju.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.model.Sort;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.AdministrativeRegion;
import lol.pbu.kaiju.repository.AdministrativeRegionRepository;
import lol.pbu.kaiju.security.Permission;
import lol.pbu.kaiju.security.SecurityRoles;
import lol.pbu.kaiju.security.AuthentikAuthenticationMapper;
import lol.pbu.kaiju.util.ControllerUtils;

import java.util.Optional;
import java.util.UUID;

import static io.micronaut.scheduling.TaskExecutors.BLOCKING;
import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;
import static lol.pbu.kaiju.security.Permission.REGION_MANAGE_CLAIM;
import static lol.pbu.kaiju.security.Permission.SYSTEM_ADMIN_CLAIM;

/**
 * REST controller for managing geographical administrative regions.
 *
 * <p>Enforces a two-tier security model: read operations require general authentication
 * ({@link SecurityRule#IS_AUTHENTICATED}), while mutating operations are restricted to
 * platform administrators and regional managers ({@link Permission#SYSTEM_ADMIN_CLAIM}, {@link Permission#REGION_MANAGE_CLAIM}).</p>
 *
 * @see SecurityRoles
 * @see Permission#SYSTEM_ADMIN_CLAIM
 * @see Permission#REGION_MANAGE_CLAIM
 * @see AuthentikAuthenticationMapper
 * @see AdministrativeRegionRepository
 */
@ExecuteOn(BLOCKING)
@Secured(IS_AUTHENTICATED)
@Controller("/administrative-regions")
public class AdministrativeRegionController implements ControllerUtils {

    private final AdministrativeRegionRepository administrativeRegionRepository;

    public AdministrativeRegionController(AdministrativeRegionRepository administrativeRegionRepository) {
        this.administrativeRegionRepository = administrativeRegionRepository;
    }

    /**
     * Retrieves a paginated list of administrative regions using cursor-based pagination.
     * Accessible to all authenticated platform roles.
     *
     * @param pageable cursor pagination parameters (cursor, limit, sort)
     * @return a {@link CursoredPage} containing administrative region entities and next cursor details
     * @see AdministrativeRegionController for controller-level security architecture
     * @see SecurityRule#IS_AUTHENTICATED
     */
    @Get
    public CursoredPage<AdministrativeRegion> getAdministrativeRegions(@Nullable CursoredPageable pageable) {
        CursoredPageable effectivePageable = (pageable == null || pageable.isUnpaged())
                ? CursoredPageable.from(20, Sort.of(Sort.Order.asc("name")))
                : pageable;
        return administrativeRegionRepository.findAll(effectivePageable);
    }

    /**
     * Retrieves an administrative region by its unique identifier.
     * Accessible to all authenticated platform roles.
     *
     * @param id the unique {@link UUID} of the administrative region
     * @return an {@link Optional} containing the matching {@link AdministrativeRegion}, or empty if not found
     * @see AdministrativeRegionController for controller-level security architecture
     * @see SecurityRule#IS_AUTHENTICATED
     */
    @Get("/{id}")
    public Optional<AdministrativeRegion> getAdministrativeRegion(@PathVariable UUID id) {
        return administrativeRegionRepository.findById(id);
    }

    /**
     * Creates and persists a new administrative region polygon.
     * Restricted to platform administrators ({@code GLOBAL_ADMIN}) and regional managers ({@code REGIONAL_ADMIN}).
     *
     * @param region the administrative region to create, including its geographic boundary polygon
     * @return the persisted {@link AdministrativeRegion} with its assigned identifier
     * @see AdministrativeRegionController for controller-level security architecture
     * @see Permission#SYSTEM_ADMIN_CLAIM
     * @see Permission#REGION_MANAGE_CLAIM
     */
    @Secured({SYSTEM_ADMIN_CLAIM, REGION_MANAGE_CLAIM})
    @Post
    public AdministrativeRegion addAdministrativeRegion(@Valid @Body AdministrativeRegion region) {
        return administrativeRegionRepository.save(region);
    }

    /**
     * Updates an existing administrative region by ID.
     * Restricted to platform administrators ({@code GLOBAL_ADMIN}) and regional managers ({@code REGIONAL_ADMIN}).
     *
     * @param id     the unique {@link UUID} of the administrative region to update
     * @param region the updated region data
     * @return the updated {@link AdministrativeRegion}
     * @see AdministrativeRegionController for controller-level security architecture
     * @see ControllerUtils#checkExists for entity existence validation
     * @see Permission#SYSTEM_ADMIN_CLAIM
     * @see Permission#REGION_MANAGE_CLAIM
     */
    @Secured({SYSTEM_ADMIN_CLAIM, REGION_MANAGE_CLAIM})
    @Put("/{id}")
    public AdministrativeRegion updateAdministrativeRegion(@PathVariable UUID id, @Valid @Body AdministrativeRegion region) {
        checkExists(administrativeRegionRepository, id);
        return administrativeRegionRepository.update(region.withId(id));
    }

    /**
     * Deletes an administrative region by its unique identifier.
     * Restricted to platform administrators ({@code GLOBAL_ADMIN}) and regional managers ({@code REGIONAL_ADMIN}).
     *
     * @param id the unique {@link UUID} of the administrative region to delete
     * @see AdministrativeRegionController for controller-level security architecture
     * @see ControllerUtils#checkExists for entity existence validation
     * @see Permission#SYSTEM_ADMIN_CLAIM
     * @see Permission#REGION_MANAGE_CLAIM
     */
    @Secured({SYSTEM_ADMIN_CLAIM, REGION_MANAGE_CLAIM})
    @Delete("/{id}")
    public void deleteAdministrativeRegion(@PathVariable UUID id) {
        checkExists(administrativeRegionRepository, id);
        administrativeRegionRepository.deleteById(id);
    }
}
