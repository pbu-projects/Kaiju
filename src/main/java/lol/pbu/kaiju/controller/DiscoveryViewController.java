package lol.pbu.kaiju.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.io.Writable;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.data.model.Sort;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.views.ViewsRenderer;
import io.swagger.v3.oas.annotations.Hidden;
import lol.pbu.kaiju.domain.Location;
import lol.pbu.kaiju.domain.Project;
import lol.pbu.kaiju.model.FeaturedProjectView;
import lol.pbu.kaiju.model.ProjectStatus;
import lol.pbu.kaiju.model.ProjectType;
import lol.pbu.kaiju.model.VerificationStatus;
import lol.pbu.kaiju.repository.ProjectRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static io.micronaut.security.rules.SecurityRule.IS_ANONYMOUS;

@Hidden
@ExecuteOn(TaskExecutors.VIRTUAL)
@Secured(IS_ANONYMOUS)
@Controller
public class DiscoveryViewController {

    private final ViewsRenderer<Map<String, Object>, HttpRequest<?>> viewsRenderer;
    private final ProjectRepository projectRepository;

    public DiscoveryViewController(
            ViewsRenderer<Map<String, Object>, HttpRequest<?>> viewsRenderer,
            ProjectRepository projectRepository
    ) {
        this.viewsRenderer = viewsRenderer;
        this.projectRepository = projectRepository;
    }

    @Hidden
    @Get("/")
    @Produces(MediaType.TEXT_HTML)
    public HttpResponse<Writable> index(HttpRequest<?> request) {
        FeaturedProjectView featuredProject = findFeaturedProject();
        Map<String, Object> model = new HashMap<>();
        model.put("featuredProject", featuredProject);
        return HttpResponse.ok(viewsRenderer.render("index", model, request))
                .contentType(MediaType.TEXT_HTML_TYPE);
    }

    @Hidden
    @Get("/projects")
    @Produces(MediaType.TEXT_HTML)
    public HttpResponse<Writable> projects(
            HttpRequest<?> request,
            @QueryValue @Nullable String q,
            @QueryValue @Nullable String location,
            @QueryValue @Nullable String type,
            @Header("HX-Request") @Nullable String hxRequest
    ) {
        Map<String, Object> model = new HashMap<>();
        model.put("query", q);
        model.put("location", location);
        model.put("type", type);
        model.put("partial", "true".equalsIgnoreCase(hxRequest));
        return HttpResponse.ok(viewsRenderer.render("projects", model, request))
                .contentType(MediaType.TEXT_HTML_TYPE);
    }

    @Nullable
    private FeaturedProjectView findFeaturedProject() {
        CursoredPage<Project> page = projectRepository.findByStatusAndDeletedAtIsNull(
                ProjectStatus.ACTIVE,
                CursoredPageable.from(1, Sort.of(Sort.Order.asc(ProjectController.DEFAULT_SORT_FIELD)))
        );
        if (page.isEmpty()) {
            return null;
        }
        return toFeaturedProjectView(page.getContent().getFirst());
    }

    private static FeaturedProjectView toFeaturedProjectView(Project project) {
        String organizationName = project.organization() != null && project.organization().name() != null
                ? project.organization().name()
                : "";
        boolean verified = project.organization() != null
                && project.organization().verificationStatus() == VerificationStatus.VERIFIED;
        return new FeaturedProjectView(
                project.id(),
                project.title(),
                project.description(),
                organizationName,
                formatProjectType(project.projectType()),
                formatLocationLabel(project),
                verified
        );
    }

    private static String formatProjectType(@Nullable ProjectType projectType) {
        if (projectType == null) {
            return "";
        }
        return switch (projectType) {
            case STANDARD -> "Standard";
            case OPEN_DOOR -> "Open Door";
            case REGIONAL -> "Regional";
        };
    }

    private static String formatLocationLabel(Project project) {
        if (project.locations() != null && !project.locations().isEmpty()) {
            Location first = project.locations().getFirst();
            if (first.city() != null && !first.city().isBlank()) {
                if (first.stateProvince() != null && !first.stateProvince().isBlank()) {
                    return first.city() + ", " + first.stateProvince();
                }
                return first.city();
            }
            if (first.name() != null && !first.name().isBlank()) {
                return first.name();
            }
        }
        if (project.managingRegion() != null && Objects.nonNull(project.managingRegion().name())) {
            return project.managingRegion().name();
        }
        return "";
    }
}
