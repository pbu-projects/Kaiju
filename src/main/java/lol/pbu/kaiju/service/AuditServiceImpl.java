package lol.pbu.kaiju.service;

import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import lol.pbu.kaiju.domain.Organization;
import lol.pbu.kaiju.domain.OrganizationAuditLog;
import lol.pbu.kaiju.domain.Project;
import lol.pbu.kaiju.domain.ProjectAuditLog;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.model.AuditAction;
import lol.pbu.kaiju.repository.OrganizationAuditLogRepository;
import lol.pbu.kaiju.repository.ProjectAuditLogRepository;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Singleton
public class AuditServiceImpl implements AuditService {

    private final ProjectAuditLogRepository projectAuditLogRepository;
    private final OrganizationAuditLogRepository organizationAuditLogRepository;

    public AuditServiceImpl(
            ProjectAuditLogRepository projectAuditLogRepository,
            OrganizationAuditLogRepository organizationAuditLogRepository
    ) {
        this.projectAuditLogRepository = projectAuditLogRepository;
        this.organizationAuditLogRepository = organizationAuditLogRepository;
    }

    @Override
    @Transactional
    @NonNull
    public ProjectAuditLog recordProjectAudit(
            @NonNull Project project,
            @NonNull User actor,
            @NonNull AuditAction action
    ) {
        ProjectAuditLog log = new ProjectAuditLog(
                null,
                project,
                actor,
                action,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        return projectAuditLogRepository.save(log);
    }

    @Override
    @Transactional
    @NonNull
    public OrganizationAuditLog recordOrganizationAudit(
            @NonNull Organization organization,
            @NonNull User actor,
            @NonNull String previousStatus,
            @NonNull String newStatus,
            @Nullable String reason
    ) {
        OrganizationAuditLog log = new OrganizationAuditLog(
                null,
                organization,
                actor,
                previousStatus,
                newStatus,
                reason,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        return organizationAuditLogRepository.save(log);
    }
}
