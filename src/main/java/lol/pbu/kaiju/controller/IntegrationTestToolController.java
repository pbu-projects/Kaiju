package lol.pbu.kaiju.controller;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.serde.annotation.Serdeable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ExecuteOn(TaskExecutors.VIRTUAL)
@Secured(SecurityRule.IS_ANONYMOUS)
@Requires(notEnv = {"prod", "production"})
@Controller
public class IntegrationTestToolController {

    private static final Logger LOG = LoggerFactory.getLogger(IntegrationTestToolController.class);
    private static final Pattern CODE_PATTERN = Pattern.compile("code=([^&]+)");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\"id_token\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ACCESS_TOKEN_PATTERN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");

    private final String htmlContent;
    private final String authentikBaseUrl;
    private final String clientId;
    private final String clientSecret;

    public IntegrationTestToolController(
            ResourceResolver resourceResolver,
            @Value("${authentik.base-url:http://localhost:9000}") String authentikBaseUrl,
            @Value("${micronaut.security.oauth2.clients.authentik.client-id:kaiju-client}") String clientId,
            @Value("${micronaut.security.oauth2.clients.authentik.client-secret:kaiju-secret}") String clientSecret
    ) {
        this.htmlContent = loadResource(resourceResolver);
        this.authentikBaseUrl = authentikBaseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
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

    @Get(value = "/integration-test-tool/api/personas")
    public List<PersonaDto> listPersonas() {
        return List.of(
                new PersonaDto(
                        "Victor Volunteer",
                        "victor.volunteer1@kaiju.local",
                        "STANDARD_USER",
                        "Standard volunteer discovering community projects and committing to open shifts"
                ),
                new PersonaDto(
                        "Jenny Niu",
                        "jenny.niu@kaiju.local",
                        "ORG_ADMIN",
                        "Organization administrator managing and submitting projects for Starfleet Volunteer Corps"
                ),
                new PersonaDto(
                        "Daniel Director",
                        "daniel.county.director1@kaiju.local",
                        "REGION_DIRECTOR",
                        "Regional director evaluating and approving community projects in Arapahoe County"
                )
        );
    }

    @Post(value = "/integration-test-tool/api/login")
    public HttpResponse<?> login(@Body LoginCommand command) {
        if (command == null || command.username() == null || command.username().isBlank()) {
            return HttpResponse.badRequest(Map.of("message", "Username or email is required."));
        }

        String username = command.username().trim();
        String password = (command.password() != null && !command.password().isBlank())
                ? command.password()
                : "KaijuCommunity2026!";

        try {
            LoginResult result = performAuthentikLogin(username, password);
            if (result == null || result.idToken() == null) {
                return HttpResponse.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "Authentik authentication failed. Check user credentials."));
            }
            return HttpResponse.ok(result);
        } catch (Exception e) {
            LOG.warn("Failed to complete Authentik login for {}: {}", username, e.getMessage());
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "Authentik IdP is unavailable: " + e.getMessage()));
        }
    }

    private LoginResult performAuthentikLogin(String username, String password) throws IOException, InterruptedException {
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        // 1. Initialize authentication flow
        HttpRequest initReq = HttpRequest.newBuilder()
                .uri(URI.create(authentikBaseUrl + "/api/v3/flows/executor/default-authentication-flow/?query="))
                .GET()
                .build();
        client.send(initReq, BodyHandlers.discarding());

        // 2. Identification stage
        String idBody = "{\"component\": \"ak-stage-identification\", \"uid_field\": \"" + escapeJson(username) + "\"}";
        HttpRequest idReq = HttpRequest.newBuilder()
                .uri(URI.create(authentikBaseUrl + "/api/v3/flows/executor/default-authentication-flow/?query="))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(idBody))
                .build();
        client.send(idReq, BodyHandlers.discarding());

        // 3. Password stage
        String pwdBody = "{\"component\": \"ak-stage-password\", \"password\": \"" + escapeJson(password) + "\"}";
        HttpRequest pwdReq = HttpRequest.newBuilder()
                .uri(URI.create(authentikBaseUrl + "/api/v3/flows/executor/default-authentication-flow/?query="))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(pwdBody))
                .build();
        client.send(pwdReq, BodyHandlers.discarding());

        // 4. Authorize stage to get authorization code
        String redirectUri = "http://localhost:8080/oauth/callback/authentik";
        String authUrl = authentikBaseUrl + "/application/o/authorize/?"
                + "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&response_type=code"
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                + "&scope=openid%20email%20profile";

        HttpRequest authReq = HttpRequest.newBuilder()
                .uri(URI.create(authUrl))
                .GET()
                .build();
        java.net.http.HttpResponse<String> authResp = client.send(authReq, BodyHandlers.ofString());

        String location = authResp.headers().firstValue("Location").orElse(null);
        if (location == null) {
            LOG.warn("No Location header returned in Authentik authorization response");
            return null;
        }

        Matcher codeMatcher = CODE_PATTERN.matcher(location);
        if (!codeMatcher.find()) {
            LOG.warn("No authorization code found in redirect URL: {}", location);
            return null;
        }
        String code = URLDecoder.decode(codeMatcher.group(1), StandardCharsets.UTF_8);

        // 5. Exchange code for tokens
        String tokenBody = "grant_type=authorization_code"
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8);

        HttpRequest tokenReq = HttpRequest.newBuilder()
                .uri(URI.create(authentikBaseUrl + "/application/o/token/"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(tokenBody))
                .build();
        java.net.http.HttpResponse<String> tokenResp = client.send(tokenReq, BodyHandlers.ofString());
        String tokenJson = tokenResp.body();

        String idToken = extractJsonField(TOKEN_PATTERN, tokenJson);
        String accessToken = extractJsonField(ACCESS_TOKEN_PATTERN, tokenJson);

        String role = resolveRoleForUser(username);
        String displayName = resolveDisplayName(username);

        return new LoginResult(idToken, accessToken, username, displayName, role);
    }

    private static String extractJsonField(Pattern pattern, String json) {
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String resolveRoleForUser(String username) {
        String lower = username.toLowerCase();
        if (lower.contains("director") || lower.contains("specialist")) {
            return "REGION_DIRECTOR";
        }
        if (lower.contains("jenny") || lower.contains("organizer") || lower.contains("admin")) {
            return "ORG_ADMIN";
        }
        return "STANDARD_USER";
    }

    private static String resolveDisplayName(String username) {
        String lower = username.toLowerCase();
        if (lower.contains("director")) {
            return "Daniel Director";
        }
        if (lower.contains("jenny")) {
            return "Jenny Niu";
        }
        if (lower.contains("victor")) {
            return "Victor Volunteer";
        }
        return username;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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

    @Serdeable
    public record LoginCommand(String username, String password) {}

    @Serdeable
    public record LoginResult(String idToken, String accessToken, String email, String name, String role) {}

    @Serdeable
    public record PersonaDto(String name, String email, String role, String description) {}
}
