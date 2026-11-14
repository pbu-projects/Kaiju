package lol.pbu.kaiju.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.swagger.v3.oas.annotations.Hidden;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static io.micronaut.security.rules.SecurityRule.IS_ANONYMOUS;

@Hidden
@ExecuteOn(TaskExecutors.VIRTUAL)
@Secured(IS_ANONYMOUS)
@Controller("/")
public class DiscoveryViewController {

    static final String DEFAULT_VIEW_PATH = "/views/discovery.html";

    private final String templatePath;

    public DiscoveryViewController() {
        this(DEFAULT_VIEW_PATH);
    }

    DiscoveryViewController(String templatePath) {
        this.templatePath = templatePath;
        loadTemplate(templatePath);
    }

    @Hidden
    @Get
    @Produces(MediaType.TEXT_HTML)
    public HttpResponse<String> index() {
        return HttpResponse.ok(loadTemplate(templatePath)).contentType(MediaType.TEXT_HTML_TYPE);
    }

    private static String loadTemplate(String path) {
        Path localPath = Arrays.stream(path.split("/"))
                .filter(segment -> !segment.isEmpty())
                .reduce(Path.of("src", "main", "resources"), Path::resolve, (first, second) -> second);
        if (Files.isRegularFile(localPath)) {
            try {
                return Files.readString(localPath, StandardCharsets.UTF_8);
            } catch (IOException _) {
                // fallback to classpath
            }
        }
        try (InputStream inputStream = DiscoveryViewController.class.getResourceAsStream(path)) {
            if (inputStream == null) {
                throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Discovery view template not found");
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException _) {
            throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read discovery view template");
        }
    }
}
