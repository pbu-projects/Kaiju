package lol.pbu.kaiju.controller;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lol.pbu.kaiju.domain.Organization;
import lol.pbu.kaiju.dto.CreateOrganizationCommand;
import lol.pbu.kaiju.dto.UpdateOrganizationCommand;
import lol.pbu.kaiju.model.VerificationStatus;
import lol.pbu.kaiju.repository.OrganizationRepository;
import lol.pbu.kaiju.repository.SecurityQueryRepository;
import lol.pbu.kaiju.repository.UserRepository;
import lol.pbu.kaiju.security.Permission;
import lol.pbu.kaiju.service.OrganizationService;
import lol.pbu.kaiju.util.PageableUtils;
import lol.pbu.kaiju.util.SpatialMappingService;
import org.locationtech.jts.geom.Point;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.micronaut.http.HttpStatus.FORBIDDEN;
import static io.micronaut.scheduling.TaskExecutors.BLOCKING;
import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;
import static lol.pbu.kaiju.security.Permission.SYSTEM_ADMIN_CLAIM;

@ExecuteOn(BLOCKING)
@Secured(IS_AUTHENTICATED)
@Controller("/organizations")
public class OrganizationController {

    public static final String DEFAULT_SORT_FIELD = "name";

    private final OrganizationService organizationService;
    private final OrganizationRepository organizationRepository;
    private final SpatialMappingService spatialMappingService;
    private final UserRepository userRepository;
    private final SecurityQueryRepository queryRepository;

    public OrganizationController(
            OrganizationService organizationService,
            OrganizationRepository organizationRepository,
            SpatialMappingService spatialMappingService,
            UserRepository userRepository,
            SecurityQueryRepository queryRepository
    ) {
        this.organizationService = organizationService;
        this.organizationRepository = organizationRepository;
        this.spatialMappingService = spatialMappingService;
        this.userRepository = userRepository;
        this.queryRepository = queryRepository;
    }

    @Secured(SYSTEM_ADMIN_CLAIM)
    @Get
    public CursoredPage<Organization> getOrganizations(@Nullable CursoredPageable pageable) {
        return organizationService.getOrganizations(PageableUtils.resolvePageable(pageable, DEFAULT_SORT_FIELD));
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

        Pageable effectivePageable = normalizePageable(pageable);
        if (exact || isQuoted) {
            return organizationRepository.searchByNameExact(unquoted, effectivePageable);
        } else {
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
            if (results.getTotalSize() == 0) {
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
        Pageable effectivePageable = normalizePageable(pageable);
        Point point = spatialMappingService.toPoint(longitude, latitude);
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
        Pageable effectivePageable = normalizePageable(pageable);
        return organizationRepository.searchByRegion(regionId, effectivePageable);
    }

    private @NonNull Pageable normalizePageable(@Nullable Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return Pageable.from(0, PageableUtils.DEFAULT_PAGE_SIZE);
        }
        if (!pageable.getSort().getOrderBy().isEmpty()) {
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
        return organizationService.getOrganizationById(id);
    }

    @Post
    public Organization addOrganization(@Valid @Body CreateOrganizationCommand command, Principal principal) {
        UUID creatorUserId = UUID.fromString(principal.getName());
        Organization organization = new Organization(
                null,
                command.name(),
                command.websiteUrl(),
                command.parentId(),
                command.isPublic(),
                VerificationStatus.UNVERIFIED,
                null,
                List.of()
        );
        return organizationService.createOrganization(organization, creatorUserId);
    }

    /**
     * Updates an existing organization by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the organization does not exist.
     *
     * @param id        the ID of the organization to update
     * @param command   the updated organization command
     * @param principal the authenticated principal
     * @return the updated organization
     */
    @Put("/{id}")
    public Organization updateOrganization(@PathVariable UUID id, @Valid @Body UpdateOrganizationCommand command, Principal principal) {
        verifyOrgAdminAuthority(principal, id);
        Organization candidate = new Organization(
                id,
                command.name(),
                command.websiteUrl(),
                command.parentId(),
                command.isPublic(),
                null,
                null,
                null
        );
        UUID actorUserId = UUID.fromString(principal.getName());
        return organizationService.updateOrganization(id, candidate, actorUserId);
    }

    /**
     * Deletes an organization by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the organization does not exist.
     *
     * @param id        the ID of the organization to delete
     * @param principal the authenticated principal
     */
    @Delete("/{id}")
    public void deleteOrganization(@PathVariable UUID id, Principal principal) {
        verifyOrgAdminAuthority(principal, id);
        organizationService.deleteOrganization(id);
    }

    private void verifyOrgAdminAuthority(Principal principal, UUID organizationId) {
        UUID callerId = UUID.fromString(principal.getName());
        boolean isSysAdmin = userRepository.findById(callerId)
                .map(u -> u.role().hasPermission(Permission.SYSTEM_ADMIN))
                .orElse(false);
        if (isSysAdmin) {
            return;
        }
        if (!queryRepository.isOrgAdmin(callerId, organizationId)) {
            throw new HttpStatusException(FORBIDDEN, "Forbidden: Only organization admins or system administrators may modify this organization");
        }
    }
}
