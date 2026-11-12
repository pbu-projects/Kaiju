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

import static io.micronaut.security.rules.SecurityRule.IS_ANONYMOUS;

@Hidden
@ExecuteOn(TaskExecutors.VIRTUAL)
@Secured(IS_ANONYMOUS)
@Controller("/")
public class DiscoveryViewController {

    static final String DEFAULT_VIEW_PATH = "/views/discovery.html";

    private final String discoveryHtml;

    public DiscoveryViewController() {
        this(DEFAULT_VIEW_PATH);
    }

    DiscoveryViewController(String templatePath) {
        this.discoveryHtml = loadTemplate(templatePath);
    }

    @Hidden
    @Get
    @Produces(MediaType.TEXT_HTML)
    public HttpResponse<String> index() {
        return HttpResponse.ok(discoveryHtml).contentType(MediaType.TEXT_HTML_TYPE);
    }

    private static String loadTemplate(String path) {
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
