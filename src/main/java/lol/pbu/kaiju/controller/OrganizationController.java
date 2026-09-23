package lol.pbu.kaiju.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.Sort;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lol.pbu.kaiju.domain.Organization;
import lol.pbu.kaiju.repository.OrganizationRepository;
import lol.pbu.kaiju.util.ControllerUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
    public CursoredPage<Organization> getOrganizations(@Nullable CursoredPageable pageable) {
        CursoredPageable effectivePageable = (pageable == null || pageable.isUnpaged())
                ? CursoredPageable.from(20, Sort.of(Sort.Order.asc("name")))
                : pageable;
        return organizationRepository.findAll(effectivePageable);
    }

    /**
     * Searches organizations by name.
     * Supports exact name matching when {@code exact=true} or when the search query is enclosed in quotes (e.g. "The Salvation Army").
     * Otherwise, performs an intelligent tiered ranking search where exact matches are ranked first, followed by prefix matches,
     * word-boundary matches, general substring matches, and typo-tolerant trigram similarity matches.
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

        boolean isQuoted = (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2)
                || (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length() >= 2);

        String unquoted = isQuoted ? trimmed.substring(1, trimmed.length() - 1).trim() : trimmed;
        if (unquoted.isEmpty()) {
            return Page.empty();
        }

        // Defend against pure wildcard injection (e.g. "%", "_", "%%")
        String strippedOfWildcards = unquoted.replace("%", "").replace("_", "").trim();
        if (strippedOfWildcards.isEmpty()) {
            return Page.empty();
        }

        if (exact || isQuoted) {
            Pageable effectivePageable = normalizePageable(pageable, false);
            return organizationRepository.searchByNameExact(unquoted, effectivePageable);
        } else {
            Pageable effectivePageable = normalizePageable(pageable, true);
            String escapedTerm = escapeSqlLike(unquoted);
            String canonicalTerm = extractCanonicalTerm(unquoted);
            String escapedCanonical = escapeSqlLike(canonicalTerm);
            Page<Organization> results = organizationRepository.searchByNameRanked(
                    unquoted,
                    escapedTerm,
                    canonicalTerm,
                    escapedCanonical,
                    effectivePageable
            );
            if (results.getContent().isEmpty()) {
                return organizationRepository.searchByNameFuzzy(
                        unquoted,
                        canonicalTerm,
                        effectivePageable
                );
            }
            return results;
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
            @QueryValue @Min(-180) @Max(180) double longitude,
            @QueryValue @Min(-90) @Max(90) double latitude,
            @QueryValue @Positive @Max(500000) double radiusMeters,
            @Valid Pageable pageable
    ) {
        Pageable effectivePageable = normalizePageable(pageable, true);
        GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
        Point point = geometryFactory.createPoint(new Coordinate(longitude, latitude));
        return organizationRepository.searchByLocation(point, radiusMeters, effectivePageable);
    }

    /**
     * Searches organizations associated with an administrative region.
     * Only returns verified, public organizations operating within the specified region.
     *
     * @param regionId the unique identifier of the administrative region
     * @param pageable pagination parameters
     * @return a page of matching organizations in the region
     */
    @Get("/search-by-region")
    public Page<Organization> searchByRegion(
            @QueryValue @NonNull UUID regionId,
            @Valid Pageable pageable
    ) {
        Pageable effectivePageable = normalizePageable(pageable, false);
        return organizationRepository.searchByRegion(regionId, effectivePageable);
    }

    private Pageable normalizePageable(Pageable pageable, boolean stripSort) {
        if (pageable == null || pageable.isUnpaged()) {
            return Pageable.from(0, 20);
        }
        if (stripSort && !pageable.getSort().getOrderBy().isEmpty()) {
            return Pageable.from(pageable.getNumber(), pageable.getSize());
        }
        return pageable;
    }

    private static String escapeSqlLike(String term) {
        return term.replace("\\", "\\\\")
                   .replace("%", "\\%")
                   .replace("_", "\\_");
    }

    private static String extractCanonicalTerm(String term) {
        return term.replaceFirst("^(?i)(the|a|an)\\s+", "").trim();
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
