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
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.Organization;
import lol.pbu.kaiju.domain.OrganizationAuditLog;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.dto.CreateOrganizationAuditLogCommand;
import lol.pbu.kaiju.dto.UpdateOrganizationAuditLogCommand;
import lol.pbu.kaiju.repository.OrganizationAuditLogRepository;
import lol.pbu.kaiju.repository.OrganizationRepository;
import lol.pbu.kaiju.repository.UserRepository;
import lol.pbu.kaiju.util.ControllerUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static io.micronaut.http.HttpStatus.NOT_FOUND;
import static io.micronaut.scheduling.TaskExecutors.BLOCKING;
import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;

@ExecuteOn(BLOCKING)
@Secured(IS_AUTHENTICATED)
@Controller("/organization-audit-logs")
public class OrganizationAuditLogController implements ControllerUtils {

    public static final String DEFAULT_SORT_FIELD = "id";

    private final OrganizationAuditLogRepository organizationAuditLogRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;

    public OrganizationAuditLogController(
            OrganizationAuditLogRepository organizationAuditLogRepository,
            OrganizationRepository organizationRepository,
            UserRepository userRepository
    ) {
        this.organizationAuditLogRepository = organizationAuditLogRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
    }

    @Get
    public CursoredPage<OrganizationAuditLog> getOrganizationAuditLogs(@Nullable @Valid CursoredPageable pageable) {
        return organizationAuditLogRepository.findAll(resolvePageable(pageable, DEFAULT_SORT_FIELD));
    }

    @Get("/{id}")
    public Optional<OrganizationAuditLog> getOrganizationAuditLog(@PathVariable UUID id) {
        return organizationAuditLogRepository.findById(id);
    }

    @Post
    public OrganizationAuditLog addOrganizationAuditLog(@Valid @Body CreateOrganizationAuditLogCommand command) {
        Organization organization = organizationRepository.findById(command.organizationId())
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "Organization not found"));
        User actor = userRepository.findById(command.actorId())
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "Actor not found"));
        OrganizationAuditLog log = new OrganizationAuditLog(
                null,
                organization,
                actor,
                command.previousStatus(),
                command.newStatus(),
                command.reason(),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        return organizationAuditLogRepository.save(log);
    }

    @Put("/{id}")
    public OrganizationAuditLog updateOrganizationAuditLog(@PathVariable UUID id, @Valid @Body UpdateOrganizationAuditLogCommand command) {
        checkExists(organizationAuditLogRepository, id);
        OrganizationAuditLog existing = organizationAuditLogRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "Organization audit log not found"));
        OrganizationAuditLog updated = new OrganizationAuditLog(
                id,
                existing.organization(),
                existing.actor(),
                command.previousStatus(),
                command.newStatus(),
                command.reason(),
                existing.createdAt()
        );
        return organizationAuditLogRepository.update(updated);
    }

    @Delete("/{id}")
    public void deleteOrganizationAuditLog(@PathVariable UUID id) {
        checkExists(organizationAuditLogRepository, id);
        organizationAuditLogRepository.deleteById(id);
    }
}
