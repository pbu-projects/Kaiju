package lol.pbu.kaiju.service;

import lol.pbu.kaiju.domain.Organization;
import lol.pbu.kaiju.domain.OrganizationAuditLog;
import lol.pbu.kaiju.domain.Project;
import lol.pbu.kaiju.domain.ProjectAuditLog;
import lol.pbu.kaiju.domain.User;
import lol.pbu.kaiju.model.AuditAction;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface AuditService {

    @NonNull
    ProjectAuditLog recordProjectAudit(
            @NonNull Project project,
            @NonNull User actor,
            @NonNull AuditAction action
    );

    @NonNull
    OrganizationAuditLog recordOrganizationAudit(
            @NonNull Organization organization,
            @NonNull User actor,
            @NonNull String previousStatus,
            @NonNull String newStatus,
            @Nullable String reason
    );
}
