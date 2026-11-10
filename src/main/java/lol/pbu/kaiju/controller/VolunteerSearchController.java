package lol.pbu.kaiju.controller;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.swagger.v3.oas.annotations.Hidden;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Hidden
@ExecuteOn(TaskExecutors.VIRTUAL)
@Secured(SecurityRule.IS_ANONYMOUS)
@Controller("/")
public class VolunteerSearchController {

    private static final String VIEW_PATH = "/views/volunteer-search.html";

    @Get
    @Produces(MediaType.TEXT_HTML)
    public String home() {
        return renderView();
    }

    @Get("/search")
    @Produces(MediaType.TEXT_HTML)
    public String search() {
        return renderView();
    }

    private String renderView() {
        try (InputStream stream = VolunteerSearchController.class.getResourceAsStream(VIEW_PATH)) {
            if (stream == null) {
                throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Volunteer search view file not found on classpath.");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read volunteer search view: " + e.getMessage());
        }
    }
}
