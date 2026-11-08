package lol.pbu.kaiju.controller;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@ExecuteOn(TaskExecutors.VIRTUAL)
@Secured(SecurityRule.IS_ANONYMOUS)
@Requires(notEnv = {"prod", "production"})
@Controller
public class IntegrationTestToolController {

    private final String htmlContent;

    public IntegrationTestToolController(ResourceResolver resourceResolver) {
        this.htmlContent = loadResource(resourceResolver);
    }

    @Get(value = "/", produces = MediaType.TEXT_HTML)
    public String root() {
        return htmlContent;
    }

    @Get(value = "/integration-test-tool", produces = MediaType.TEXT_HTML)
    public String testTool() {
        return htmlContent;
    }

    @Get(value = "/dashboard", produces = MediaType.TEXT_HTML)
    public String dashboardAlias() {
        return htmlContent;
    }

    private static String loadResource(ResourceResolver resourceResolver) {
        Optional<InputStream> stream = resourceResolver.getResourceAsStream("classpath:views/integration-test-tool.html");
        if (stream.isPresent()) {
            try (InputStream in = stream.get()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "<html><body><h1>Error loading Integration Testing Tool</h1></body></html>";
            }
        }
        return "<html><body><h1>Kaiju Integration Testing Tool</h1><p>Tooling resource not found.</p></body></html>";
    }
}
