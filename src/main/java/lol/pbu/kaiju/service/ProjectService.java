package lol.pbu.kaiju.service;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import lol.pbu.kaiju.domain.Project;
import org.jspecify.annotations.NonNull;

import java.util.Optional;
import java.util.UUID;

public interface ProjectService {

    @NonNull
    CursoredPage<Project> getProjects(@NonNull CursoredPageable pageable);

    @NonNull
    Optional<Project> getProjectById(@NonNull UUID id);

    @NonNull
    Project createProject(@NonNull Project project, @NonNull UUID actorUserId);

    @NonNull
    Project updateProject(@NonNull UUID id, @NonNull Project project, @NonNull UUID actorUserId);

    void deleteProject(@NonNull UUID id, @NonNull UUID actorUserId);

    @NonNull
    Project approveProject(@NonNull UUID id, @NonNull UUID actorUserId);

    @NonNull
    Project rejectProject(@NonNull UUID id, @NonNull UUID actorUserId);
}
