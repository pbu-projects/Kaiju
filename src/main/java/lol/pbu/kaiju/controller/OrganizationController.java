package lol.pbu.kaiju.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.Organization;
import lol.pbu.kaiju.repository.OrganizationRepository;
import lol.pbu.kaiju.util.ControllerUtils;
import org.jspecify.annotations.NonNull;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

import java.util.Optional;
import java.util.UUID;

import static io.micronaut.http.HttpStatus.NOT_FOUND;
import static io.micronaut.scheduling.TaskExecutors.BLOCKING;
import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;
import static lol.pbu.kaiju.security.Permission.SYSTEM_ADMIN_CLAIM;

@ExecuteOn(BLOCKING)
@Secured(IS_AUTHENTICATED)
@Controller("/organizations")
public class OrganizationController implements ControllerUtils {

    private final OrganizationRepository organizationRepository;

    public OrganizationController(OrganizationRepository organizationRepository) {
        this.organizationRepository = organizationRepository;
    }

    @Secured(SYSTEM_ADMIN_CLAIM)
    @Get
    public CursoredPage<Organization> getOrganizations(@Valid CursoredPageable pageable) {
        return organizationRepository.findAll(pageable);
    }

    /**
     * Searches organizations by name.
     * Supports exact name matching when {@code exact=true} or when the search query is enclosed in quotes (e.g. "The Salvation Army").
     * Otherwise, performs an intelligent tiered ranking search where exact matches are ranked first, followed by prefix matches,
     * word-boundary matches, and general substring matches.
     *
     * @param name     the organization name or query string
     * @param exact    whether to force an exact case-insensitive match
     * @param pageable pagination parameters
     * @return a page of matching organizations
     */
    @Get("/search-by-name")
    public Page<Organization> searchByName(
            @QueryValue @NonNull String name,
            @QueryValue(defaultValue = "false") boolean exact,
            @Valid Pageable pageable
    ) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return Page.empty();
        }

        Pageable effectivePageable = pageable != null ? pageable : Pageable.from(0, 20);
        boolean isQuoted = (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2)
                || (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length() >= 2);

        if (exact || isQuoted) {
            String searchTerm = isQuoted ? trimmed.substring(1, trimmed.length() - 1).trim() : trimmed;
            return organizationRepository.searchByNameExact(searchTerm, effectivePageable);
        } else {
            return organizationRepository.searchByNameRanked(trimmed, effectivePageable);
        }
    }

    /**
     * Searches organizations by geographic coordinates and radius.
     * Finds organizations associated with locations within {@code radiusMeters} of the given point,
     * ordered by distance ascending.
     *
     * @param longitude    the longitude of the center point
     * @param latitude     the latitude of the center point
     * @param radiusMeters the search radius in meters
     * @param pageable     pagination parameters
     * @return a page of organizations within range, closest first
     */
    @Get("/search-by-location")
    public Page<Organization> searchByLocation(
            @QueryValue double longitude,
            @QueryValue double latitude,
            @QueryValue double radiusMeters,
            @Valid Pageable pageable
    ) {
        Pageable effectivePageable = pageable != null ? pageable : Pageable.from(0, 20);
        GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
        Point point = geometryFactory.createPoint(new Coordinate(longitude, latitude));
        return organizationRepository.searchByLocation(point, radiusMeters, effectivePageable);
    }

    @Get("/{id}")
    public Optional<Organization> getOrganization(@PathVariable UUID id) {
        return organizationRepository.findById(id);
    }

    @Post
    public Organization addOrganization(@Valid @Body Organization organization) {
        return organizationRepository.save(organization);
    }

    /**
     * Updates an existing organization by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the organization does not exist.
     *
     * @param id           the ID of the organization to update
     * @param organization the updated organization details
     * @return the updated organization
     */
    @Put("/{id}")
    public Organization updateOrganization(@PathVariable UUID id, @Valid @Body Organization organization) {
        Organization existing = organizationRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "Organization not found"));
        
        Organization secureOrganization = new Organization(
                id,
                organization.name(),
                organization.websiteUrl(),
                organization.parentId(),
                organization.isPublic(),
                existing.verificationStatus(),
                existing.verificationExpiresAt(),
                existing.locations()
        );
        return organizationRepository.update(secureOrganization);
    }


    /**
     * Deletes an organization by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the organization does not exist.
     *
     * @param id the ID of the organization to delete
     */
    @Delete("/{id}")
    public void deleteOrganization(@PathVariable UUID id) {
        checkExists(organizationRepository, id);
        organizationRepository.deleteById(id);
    }

}
