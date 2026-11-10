package lol.pbu.kaiju.controller;

import com.nimbusds.jose.util.JSONObjectUtils;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.Hidden;

import java.io.IOException;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Hidden
@ExecuteOn(TaskExecutors.VIRTUAL)
@Secured(SecurityRule.IS_ANONYMOUS)
@Requires(notEnv = {"prod", "production"})
@Controller
public class JourneyHarnessController {

    private static final String DEFAULT_PASSWORD = "KaijuCommunity2026!";
    private static final String VIEW_PATH = "/views/journey-harness.html";

    private final String authentikBaseUrl;
    private final String clientId;
    private final String clientSecret;
    private final EmbeddedServer embeddedServer;

    public JourneyHarnessController(
            @Value("${kaiju.authentik.base-url:`http://localhost:9000`}") String authentikBaseUrl,
            @Value("${micronaut.security.oauth2.clients.authentik.client-id:kaiju-client}") String clientId,
            @Value("${micronaut.security.oauth2.clients.authentik.client-secret:kaiju-secret}") String clientSecret,
            EmbeddedServer embeddedServer
    ) {
        this.authentikBaseUrl = authentikBaseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.embeddedServer = embeddedServer;
    }

    @Get
    @Produces(MediaType.TEXT_HTML)
    public String index() {
        return renderHarnessView();
    }

    @Get("/harness")
    @Produces(MediaType.TEXT_HTML)
    public String harness() {
        return renderHarnessView();
    }

    @Get("/harness/api/personas")
    public List<HarnessPersona> getPersonas() {
        return List.of(
                new HarnessPersona(
                        null,
                        "anonymous",
                        "Anonymous Visitor (No Token)",
                        "ANONYMOUS",
                        null,
                        "None",
                        null,
                        null,
                        "None",
                        null,
                        false,
                        "Unauthenticated visitor with no JWT bearer token. Tests public project discovery vs 401 Unauthorized on secured routes."
                ),
                new HarnessPersona(
                        "c0000000-0000-0000-0000-000000000025",
                        "victor.volunteer1@kaiju.local",
                        "Victor Volunteer 1",
                        "STANDARD_USER",
                        null,
                        "None",
                        null,
                        null,
                        "None",
                        null,
                        true,
                        "Independent volunteer seeded in PostgreSQL and Authentik for Journey 1 discovery and shift sign-up testing."
                ),
                new HarnessPersona(
                        "c0000000-0000-0000-0000-000000000099",
                        "jenny.niu@kaiju.local",
                        "Jenny Niu (JIT Volunteer)",
                        "STANDARD_USER",
                        null,
                        "None",
                        null,
                        null,
                        "None",
                        null,
                        false,
                        "Exists in Authentik but omitted from initial database seed to test Just-In-Time (JIT) user creation on first login."
                ),
                new HarnessPersona(
                        "c0000000-0000-0000-0000-000000000021",
                        "leo.local.admin1@kaiju.local",
                        "Leo Local Admin 1 (Denver Metro Branch)",
                        "STANDARD_USER",
                        "a0000000-0000-0000-0000-000000000002",
                        "Denver County Region",
                        null,
                        "b0000000-0000-0000-0000-000000000004",
                        "Denver Metro Branch",
                        "ORG_ADMIN",
                        true,
                        "Organization Admin for Denver Metro Branch (VERIFIED). Tests Journey 2 project wizard and Journey 4 volunteer management."
                ),
                new HarnessPersona(
                        "c0000000-0000-0000-0000-000000000016",
                        "steven.state.admin1@kaiju.local",
                        "Steven State Admin 1 (Colorado State Chapter)",
                        "STANDARD_USER",
                        "a0000000-0000-0000-0000-000000000001",
                        "Colorado State Region",
                        null,
                        "b0000000-0000-0000-0000-000000000002",
                        "Colorado State Chapter",
                        "ORG_ADMIN",
                        true,
                        "Organization Admin for Colorado State Chapter (VERIFIED). Sponsors Arapahoe County and statewide projects."
                ),
                new HarnessPersona(
                        "c0000000-0000-0000-0000-000000000008",
                        "connie.county.agent@kaiju.local",
                        "Connie County Agent (Denver County Specialist)",
                        "REGION_AGENT",
                        "a0000000-0000-0000-0000-000000000002",
                        "Denver County Region",
                        "REGION_AGENT",
                        null,
                        "None",
                        null,
                        true,
                        "Regional Specialist (REGION_AGENT) for Denver County. Can review, edit, and approve projects inside Denver County."
                ),
                new HarnessPersona(
                        "c0000000-0000-0000-0000-000000000006",
                        "daniel.county.director1@kaiju.local",
                        "Daniel County Director 1 (Denver County)",
                        "REGION_DIRECTOR",
                        "a0000000-0000-0000-0000-000000000002",
                        "Denver County Region",
                        "REGION_DIRECTOR",
                        null,
                        "None",
                        null,
                        true,
                        "Regional Director for Denver County. Has full regional authority in Denver, but receives 403 Forbidden in Arapahoe or El Paso County."
                ),
                new HarnessPersona(
                        "c0000000-0000-0000-0000-000000000009",
                        "ethan.sibling.director1@kaiju.local",
                        "Ethan Sibling Director 1 (El Paso County Out-of-Region)",
                        "REGION_DIRECTOR",
                        "a0000000-0000-0000-0000-000000000003",
                        "El Paso County Region",
                        "REGION_DIRECTOR",
                        null,
                        "None",
                        null,
                        true,
                        "Out-of-region Regional Director assigned to El Paso County. Used in Journey 3 to verify 403 Forbidden cross-boundary containment."
                ),
                new HarnessPersona(
                        "c0000000-0000-0000-0000-000000000003",
                        "rachel.state.director1@kaiju.local",
                        "Rachel State Director 1 (Colorado State)",
                        "REGION_DIRECTOR",
                        "a0000000-0000-0000-0000-000000000001",
                        "Colorado State Region",
                        "REGION_DIRECTOR",
                        null,
                        "None",
                        null,
                        true,
                        "Level 1 State Regional Director covering all of Colorado, including Denver County, Arapahoe County, and El Paso County."
                ),
                new HarnessPersona(
                        "c0000000-0000-0000-0000-000000000001",
                        "sarah.admin1@kaiju.local",
                        "Sarah Admin 1 (Global System Admin)",
                        "GLOBAL_ADMIN",
                        null,
                        "All Regions",
                        null,
                        null,
                        "All Organizations",
                        null,
                        true,
                        "Global System Administrator with unrestricted platform permissions across all organizations, users, and regions."
                )
        );
    }

    @Post("/harness/api/login")
    public HarnessLoginResponse login(@Body HarnessLoginRequest request) {
        if (request == null || request.email() == null || request.email().isBlank()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Persona email is required.");
        }
        String trimmedEmail = request.email().trim();
        if ("anonymous".equalsIgnoreCase(trimmedEmail)) {
            return new HarnessLoginResponse(
                    false,
                    "anonymous",
                    null,
                    null,
                    null,
                    "Anonymous visitor mode active. Requests will be sent without an Authorization header."
            );
        }
        String effectivePassword = (request.password() == null || request.password().isBlank())
                ? DEFAULT_PASSWORD
                : request.password();
        return authenticateWithAuthentik(trimmedEmail, effectivePassword);
    }

    @Get("/harness/api/journeys")
    public HarnessSpecReport getJourneys() {
        List<HarnessJourney> journeys = List.of(
                buildJourney1(),
                buildJourney2(),
                buildJourney3(),
                buildJourney4()
        );
        List<HarnessStep> allSteps = journeys.stream()
                .flatMap(j -> j.steps().stream())
                .toList();
        return new HarnessSpecReport(
                "docs/functional-spec/05-key-user-journeys.adoc",
                allSteps.size(),
                countByStatus(allSteps, "READY"),
                countByStatus(allSteps, "PARTIAL"),
                countByStatus(allSteps, "MISSING"),
                journeys
        );
    }

    private String renderHarnessView() {
        try (InputStream stream = JourneyHarnessController.class.getResourceAsStream(VIEW_PATH)) {
            if (stream == null) {
                throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Harness view file not found on classpath.");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read harness view: " + e.getMessage());
        }
    }

    private HarnessLoginResponse authenticateWithAuthentik(String email, String password) {
        try {
            Map<String, String> cookies = new LinkedHashMap<>();
            CookieHandler cookieHandler = createCookieJar(cookies);

            HttpClient flowClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .cookieHandler(cookieHandler)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            URI flowUri = URI.create(authentikBaseUrl + "/api/v3/flows/executor/default-authentication-flow/?query=");
            HttpRequest initReq = HttpRequest.newBuilder().uri(flowUri).timeout(Duration.ofSeconds(5)).GET().build();
            flowClient.send(initReq, BodyHandlers.ofString());

            String idPayload = "{\"component\":\"ak-stage-identification\",\"uid_field\":\"" + escapeJson(email) + "\"}";
            HttpRequest idReq = HttpRequest.newBuilder()
                    .uri(flowUri)
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(idPayload))
                    .build();
            flowClient.send(idReq, BodyHandlers.ofString());

            String passPayload = "{\"component\":\"ak-stage-password\",\"password\":\"" + escapeJson(password) + "\"}";
            HttpRequest passReq = HttpRequest.newBuilder()
                    .uri(flowUri)
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(passPayload))
                    .build();
            flowClient.send(passReq, BodyHandlers.ofString());

            HttpClient authClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .cookieHandler(cookieHandler)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            String redirectUri = "http://localhost:" + embeddedServer.getPort() + "/oauth/callback/authentik";
            String authorizeUrl = authentikBaseUrl + "/application/o/authorize/?"
                    + "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&response_type=code"
                    + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                    + "&scope=openid%20email%20profile";

            HttpRequest authReq = HttpRequest.newBuilder()
                    .uri(URI.create(authorizeUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> authResp = authClient.send(authReq, BodyHandlers.ofString());

            String redirectLocation = authResp.headers().firstValue("Location").orElse(null);
            if (redirectLocation == null) {
                throw new HttpStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Authentik did not issue an authorization redirect for " + email + " (HTTP " + authResp.statusCode() + "). Verify credentials and that Authentik is running."
                );
            }

            String code = extractQueryParam(URI.create(redirectLocation).getQuery(), "code");
            if (code == null) {
                throw new HttpStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Authorization code missing from Authentik redirect URL: " + redirectLocation
                );
            }

            String tokenForm = "grant_type=authorization_code"
                    + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
                    + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                    + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8);

            HttpRequest tokenReq = HttpRequest.newBuilder()
                    .uri(URI.create(authentikBaseUrl + "/application/o/token/"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(tokenForm))
                    .build();
            HttpResponse<String> tokenResp = authClient.send(tokenReq, BodyHandlers.ofString());

            Map<String, Object> tokenJson = JSONObjectUtils.parse(tokenResp.body());
            String idToken = tokenJson.get("id_token") instanceof String s ? s : null;
            String accessToken = tokenJson.get("access_token") instanceof String s ? s : null;
            if (idToken == null || idToken.isBlank()) {
                throw new HttpStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Authentik token response did not include an id_token for " + email
                );
            }

            return new HarnessLoginResponse(
                    true,
                    email,
                    idToken,
                    accessToken,
                    idToken,
                    "Authenticated " + email + " via live Authentik OIDC flow."
            );
        } catch (HttpStatusException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HttpStatusException(HttpStatus.BAD_GATEWAY, "Interrupted while contacting Authentik OIDC server: " + e.getMessage());
        } catch (Exception e) {
            throw new HttpStatusException(HttpStatus.BAD_GATEWAY, "Failed to authenticate with Authentik at " + authentikBaseUrl + ": " + e.getMessage());
        }
    }

    private static CookieHandler createCookieJar(Map<String, String> cookies) {
        return new CookieHandler() {
            @Override
            public Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders) {
                if (cookies.isEmpty()) {
                    return Map.of();
                }
                String cookieHeader = cookies.entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                        .reduce((left, right) -> left + "; " + right)
                        .orElse("");
                return Map.of("Cookie", List.of(cookieHeader));
            }

            @Override
            public void put(URI uri, Map<String, List<String>> responseHeaders) {
                responseHeaders.entrySet().stream()
                        .filter(entry -> "Set-Cookie".equalsIgnoreCase(entry.getKey()))
                        .flatMap(entry -> entry.getValue().stream())
                        .forEach(header -> parseCookieHeader(header, cookies));
            }
        };
    }

    private static void parseCookieHeader(String header, Map<String, String> cookies) {
        String[] parts = header.split(";");
        String[] nameValue = parts[0].split("=", 2);
        if (nameValue.length != 2) {
            return;
        }
        String name = nameValue[0].trim();
        String value = nameValue[1].trim();
        boolean isExpired = Arrays.stream(parts)
                .map(part -> part.trim().toLowerCase(Locale.ROOT))
                .anyMatch(trimmed -> "max-age=0".equals(trimmed) || trimmed.startsWith("max-age=-"));
        if (isExpired || value.isEmpty() || "\"\"".equals(value)) {
            cookies.remove(name);
        } else {
            cookies.put(name, value);
        }
    }

    @Nullable
    private static String extractQueryParam(@Nullable String query, String paramName) {
        if (query == null || query.isBlank()) {
            return null;
        }
        return Arrays.stream(query.split("&"))
                .map(pair -> pair.split("=", 2))
                .filter(parts -> parts.length == 2 && paramName.equals(parts[0]))
                .map(parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8))
                .findFirst()
                .orElse(null);
    }

    private static String escapeJson(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static int countByStatus(List<HarnessStep> steps, String status) {
        return (int) steps.stream()
                .filter(step -> status.equals(step.status()))
                .count();
    }

    private static HarnessJourney buildJourney(int number, String title, String summary, List<HarnessStep> steps) {
        return new HarnessJourney(
                number,
                title,
                summary,
                steps.size(),
                countByStatus(steps, "READY"),
                countByStatus(steps, "PARTIAL"),
                countByStatus(steps, "MISSING"),
                steps
        );
    }

    private static HarnessJourney buildJourney1() {
        List<HarnessStep> steps = List.of(
                new HarnessStep(
                        "1.1",
                        1,
                        "STEP",
                        "Volunteer lands on the home page or /projects, signed in or not",
                        List.of("CUR-001"),
                        "READY",
                        "GET /projects",
                        "Anonymous and signed-in visitors can land on /projects and browse active projects without receiving 401 Unauthorized. GET /projects is accessible anonymously."
                ),
                new HarnessStep(
                        "1.2",
                        1,
                        "STEP",
                        "Location defaults from IP or geolocation; volunteer may override city, ZIP, and radius",
                        List.of("CUR-001"),
                        "PARTIAL",
                        "GET /projects/search-by-location",
                        "GET /projects/search-by-location supports anonymous discovery with pre-resolved coordinates. Geocoding a human-readable city or ZIP into latitude and longitude is performed client-side."
                ),
                new HarnessStep(
                        "1.3",
                        1,
                        "STEP",
                        "Volunteer optionally applies filters (date, day, time, attributes, interests, skills) or a keyword",
                        List.of("CUR-003"),
                        "PARTIAL",
                        "GET /projects?title=...",
                        "GET /projects supports exact title filtering via the title query parameter, and GET /tags lists tags. Filtering projects by date range, day of week, time of day, attributes, interests, skills, or full-text keyword across descriptions is not implemented."
                ),
                new HarnessStep(
                        "1.4",
                        1,
                        "DECISION",
                        "DECISION: Does the volunteer want to browse a list or a map? Toggles view over the same result set",
                        List.of("CUR-002"),
                        "PARTIAL",
                        "GET /projects/search-by-location",
                        "GET /projects/search-by-location returns ProjectSearchCard items with projectId, projectTitle, locationId, locationName, and nextShiftStart. Because ProjectSearchCard omits latitude and longitude, a map view must fetch GET /locations/{id} or GET /projects/{id} to plot coordinates from the same result set."
                ),
                new HarnessStep(
                        "1.5",
                        1,
                        "STEP",
                        "Volunteer opens a project of interest, reviewing description, location, sponsor, point of contact, and attribute badges",
                        List.of("CUR-010"),
                        "PARTIAL",
                        "GET /projects/{id}",
                        "GET /projects/{id} returns title, description, projectType, status, organization, and managingRegion. Point of contact details and project-level attribute badges are not exposed on the Project response."
                ),
                new HarnessStep(
                        "1.6",
                        1,
                        "DECISION",
                        "DECISION: Is there more than one date or shift? Volunteer selects a specific occurrence",
                        List.of("CUR-012"),
                        "PARTIAL",
                        "GET /shifts",
                        "GET /shifts returns paginated shifts with startTime, endTime, isVirtual, location, and tags, and GET /shifts/{id} fetches a single shift. However, ShiftController lacks a query parameter to filter shifts by projectId on the server, requiring client-side filtering."
                ),
                new HarnessStep(
                        "1.7",
                        1,
                        "FAILURE_STATE",
                        "FAILURE STATE: Selected occurrence is at capacity; sign-up is blocked with a clear message",
                        List.of("CUR-020", "ENH-015", "ENH-061"),
                        "MISSING",
                        "POST /shift-signups (Not Implemented)",
                        "The database schema defines shifts.capacity and the shift_signups table, but the Java Shift record and CreateShiftCommand omit the capacity field, and no shift sign-up service or capacity check exists in the application layer."
                ),
                new HarnessStep(
                        "1.8",
                        1,
                        "STEP",
                        "Volunteer clicks Volunteer; prompted to sign in if needed, except for external-link projects",
                        List.of("CUR-033", "CUR-034", "ENH-017"),
                        "MISSING",
                        "POST /shift-signups (Not Implemented)",
                        "Authentik OIDC sign-in and JIT user provisioning work, and the shift_signups table exists in PostgreSQL. However, no ShiftSignup domain model, repository, or POST /shift-signups endpoint exists, and external-link project redirect handling (ENH-017) is not implemented."
                ),
                new HarnessStep(
                        "1.9",
                        1,
                        "STEP",
                        "Sign-up is confirmed, organizer is notified by email, and volunteer may export to personal calendar",
                        List.of("CUR-046", "CUR-024"),
                        "MISSING",
                        "GET /shifts/{id}/calendar (Not Implemented)",
                        "No email notification service or SMTP integration exists to alert organizers on sign-up (CUR-046), and no iCal or ICS calendar export endpoint exists for shifts (CUR-024)."
                ),
                new HarnessStep(
                        "1.10",
                        1,
                        "STEP",
                        "Commitment appears under My Dashboard -> Upcoming",
                        List.of("CUR-022"),
                        "MISSING",
                        "GET /users/me/commitments (Not Implemented)",
                        "GET /users/me returns the signed-in user's id, email, role, and createdAt. There is no endpoint to list a volunteer's upcoming shift commitments."
                ),
                new HarnessStep(
                        "1.11",
                        1,
                        "DECISION",
                        "DECISION: Plans change; volunteer cancels via Unvolunteer and capacity is restored",
                        List.of("CUR-021"),
                        "MISSING",
                        "DELETE /shift-signups/{id} (Not Implemented)",
                        "The shift_signups table supports a CANCELLED status in PostgreSQL, but no endpoint or service method exists for a volunteer to cancel a shift sign-up or restore available capacity."
                ),
                new HarnessStep(
                        "1.12",
                        1,
                        "FAILURE_STATE",
                        "FAILURE STATE: Unvolunteer must never silently fail and must return explicit confirmation",
                        List.of("ENH-021"),
                        "MISSING",
                        "DELETE /shift-signups/{id} (Not Implemented)",
                        "Because the Unvolunteer endpoint and service logic are not yet built, explicit cancellation confirmation and capacity restoration guarantees cannot be verified yet."
                )
        );
        return buildJourney(
                1,
                "Journey 1: Volunteer Discovers and Commits to a Project",
                "Tests public vs signed-in project discovery, PostGIS location search, list/map toggling, shift inspection, and volunteer sign-up/unvolunteer lifecycle.",
                steps
        );
    }

    private static HarnessJourney buildJourney2() {
        List<HarnessStep> steps = List.of(
                new HarnessStep(
                        "2.1",
                        2,
                        "STEP",
                        "Prospective organization reads /content/add-an-organization and submits a /contact referral request",
                        List.of("CUR-052"),
                        "MISSING",
                        "POST /contact (Not Implemented)",
                        "Neither the informational /content/add-an-organization page nor the /contact referral submission endpoint is implemented."
                ),
                new HarnessStep(
                        "2.2",
                        2,
                        "STEP",
                        "Specialist creates the organization record with name, slug, description, address, and contact details",
                        List.of("CUR-026"),
                        "PARTIAL",
                        "POST /organizations",
                        "POST /organizations creates an organization with name, websiteUrl, parentId, isPublic, and UNVERIFIED status. Permanent URL slug, organization description, verified address binding, and contact details are not present in CreateOrganizationCommand."
                ),
                new HarnessStep(
                        "2.3",
                        2,
                        "STEP",
                        "Specialist or administrator grants Organization Admin rights to a named individual at the organization",
                        List.of("CUR-026", "CUR-027"),
                        "READY",
                        "POST /organization-users",
                        "POST /organization-users assigns ORG_ADMIN, ORG_MANAGER, or ORG_MEMBER roles to a user for an organization, enforcing that the caller is an organization admin or system admin."
                ),
                new HarnessStep(
                        "2.4",
                        2,
                        "DECISION",
                        "DECISION: Is this the organization's only admin? That admin becomes Lead Organization Admin by default",
                        List.of("CUR-027"),
                        "PARTIAL",
                        "POST /organization-users",
                        "OrganizationUserRole supports ORG_ADMIN, ORG_MANAGER, and ORG_MEMBER, but has no distinct LEAD_ORG_ADMIN role or automatic promotion rule when adding the first administrator."
                ),
                new HarnessStep(
                        "2.5",
                        2,
                        "STEP",
                        "Org admin signs in and submits a project through the Project Wizard (Overview, Location, Date/Time, Details)",
                        List.of("CUR-011"),
                        "PARTIAL",
                        "POST /projects, POST /locations, POST /shifts",
                        "Core project creation works across POST /projects (with inline locations and boundaries), POST /locations, and POST /shifts. However, photo or file attachments and preview draft state management are not implemented."
                ),
                new HarnessStep(
                        "2.6",
                        2,
                        "FAILURE_STATE",
                        "FAILURE STATE: Save or submit fails with specific, actionable validation errors rather than a generic error",
                        List.of("ENH-007"),
                        "READY",
                        "POST /projects",
                        "Micronaut validation and service checks return structured 400 Bad Request responses with specific field messages (such as title length, description minimum 20 characters, required boundaries, and shift virtual vs location rules) rather than generic 500 errors."
                ),
                new HarnessStep(
                        "2.7",
                        2,
                        "STEP",
                        "Project publishes in a Project Under Review state and is live for volunteer sign-up before specialist approval",
                        List.of("CUR-018"),
                        "PARTIAL",
                        "POST /projects",
                        "ProjectSecurityService sets unverified or out-of-region projects to PENDING status (and verified in-region org manager projects to ACTIVE). However, GET /projects/search-by-location filters strictly by p.status = 'ACTIVE', so PENDING projects are hidden from location search until approved."
                ),
                new HarnessStep(
                        "2.8",
                        2,
                        "STEP",
                        "Org admin links the project to their organization and optionally saves it as a template for reuse",
                        List.of("CUR-032", "CUR-015"),
                        "PARTIAL",
                        "POST /projects",
                        "Every project requires an organizationId linking it to the sponsoring organization (CUR-032). Saving a project as a reusable template (CUR-015) is not implemented."
                ),
                new HarnessStep(
                        "2.9",
                        2,
                        "STEP",
                        "Project appears on the organization's public page and in search",
                        List.of("CUR-001", "CUR-026"),
                        "PARTIAL",
                        "GET /projects/search-by-location",
                        "ACTIVE projects with upcoming shifts appear in GET /projects/search-by-location and GET /projects, and organizations appear in GET /organizations/search-by-name. There is no dedicated endpoint to list all public projects for a single organization ID or slug."
                )
        );
        return buildJourney(
                2,
                "Journey 2: Organization Gets Onboarded and Posts a Project",
                "Tests organization referral and creation, admin assignment, multi-step project wizard submission, ENH-007 actionable validation errors, and under-review visibility.",
                steps
        );
    }

    private static HarnessJourney buildJourney3() {
        List<HarnessStep> steps = List.of(
                new HarnessStep(
                        "3.1",
                        3,
                        "STEP",
                        "Project submission auto-assigns to the specialist responsible for the project's boundary",
                        List.of("CUR-018"),
                        "PARTIAL",
                        "POST /projects",
                        "Projects accept an optional managingRegionId validated against PostGIS region polygons, and jurisdiction checks use spatial containment (ST_Covers) against project locations. However, automatic lookup of managingRegionId from project coordinates when omitted is not performed on save."
                ),
                new HarnessStep(
                        "3.2",
                        3,
                        "STEP",
                        "Specialist sees the project in their Pending Projects queue",
                        List.of("CUR-042"),
                        "PARTIAL",
                        "GET /projects",
                        "Specialists can fetch projects via GET /projects, which includes status and managingRegion. However, there is no server-side filter on GET /projects for status=PENDING or for the caller's assigned regional boundaries."
                ),
                new HarnessStep(
                        "3.3",
                        3,
                        "FAILURE_STATE",
                        "FAILURE STATE: Pending queue must never be empty when pending projects exist in the specialist's scope",
                        List.of("ENH-038"),
                        "PARTIAL",
                        "GET /projects",
                        "GET /projects returns all projects in cursored pages without dropping pending records, and PostGIS ST_Covers accurately checks multi-polygon hierarchy containment. A dedicated scoped pending queue query is still needed so specialists do not have to filter pages client-side."
                ),
                new HarnessStep(
                        "3.4",
                        3,
                        "STEP",
                        "Specialist reviews project content against Community Guidelines and may edit the project directly",
                        List.of("CUR-019"),
                        "READY",
                        "PUT /projects/{id}",
                        "PUT /projects/{id} allows an in-region specialist (REGION_AGENT or REGION_DIRECTOR with jurisdiction over all project locations) to edit project title, description, type, locations, and boundaries directly, recording an EDITED audit log."
                ),
                new HarnessStep(
                        "3.5",
                        3,
                        "DECISION",
                        "DECISION: Does the project comply? Specialist approves or rejects the project",
                        List.of("CUR-018", "CUR-019"),
                        "PARTIAL",
                        "PUT /projects/{id}/status",
                        "PUT /projects/{id}/status approves a PENDING or PENDING_UPDATE project when called by a specialist with geographic jurisdiction, transitioning it to ACTIVE and recording an APPROVED audit log. While ProjectServiceImpl.rejectProject exists in Java, ProjectController has no REST endpoint to trigger rejection."
                ),
                new HarnessStep(
                        "3.6",
                        3,
                        "DECISION",
                        "DECISION: Does the specialist act within the review window? Project escalates across 3/3/7-day tiers",
                        List.of("CUR-018"),
                        "MISSING",
                        "Scheduled Escalation Job (Not Implemented)",
                        "The 3-tier administrative_regions hierarchy (State, County, City) is seeded in PostGIS, but no scheduled job or escalation tracking exists to escalate unreviewed projects after 3, 6, and 13 days."
                ),
                new HarnessStep(
                        "3.7",
                        3,
                        "FAILURE_STATE",
                        "FAILURE STATE: Routing and approval authority derive strictly from the boundary and role model",
                        List.of("ENH-038", "CUR-039", "CUR-040"),
                        "PARTIAL",
                        "PUT /projects/{id}/status",
                        "ProjectSecurityService.authorizeRegionalAdminApproval strictly verifies PostGIS spatial containment across all project locations and blocks out-of-region specialists with 403 Forbidden. Escalation tier routing is not yet implemented."
                )
        );
        return buildJourney(
                3,
                "Journey 3: Specialist Reviews and Approves a Submitted Project",
                "Tests regional boundary routing, pending project queue inspection, in-region vs out-of-region specialist jurisdiction, direct project editing, approval vs rejection, and escalation tiers.",
                steps
        );
    }

    private static HarnessJourney buildJourney4() {
        List<HarnessStep> steps = List.of(
                new HarnessStep(
                        "4.1",
                        4,
                        "STEP",
                        "Organizer opens Manage Volunteers or Email Volunteers for their project",
                        List.of("CUR-025"),
                        "MISSING",
                        "GET /projects/{id}/volunteers (Not Implemented)",
                        "Although shift_signups exists in PostgreSQL, there is no endpoint or service to list volunteers registered for a project."
                ),
                new HarnessStep(
                        "4.2",
                        4,
                        "DECISION",
                        "DECISION: Is this a multi-shift project? Organizer views and filters volunteers by specific shift",
                        List.of("CUR-025", "ENH-040"),
                        "MISSING",
                        "GET /shifts/{id}/volunteers (Not Implemented)",
                        "GET /shifts lists shifts for selection, but there is no endpoint to query shift_signups by shiftId so organizers can filter volunteers per occurrence."
                ),
                new HarnessStep(
                        "4.3",
                        4,
                        "STEP",
                        "Organizer composes and sends a message to some or all signed-up volunteers",
                        List.of("CUR-025"),
                        "MISSING",
                        "POST /projects/{id}/messages (Not Implemented)",
                        "No messaging endpoint, message persistence table, or email dispatch service is implemented."
                ),
                new HarnessStep(
                        "4.4",
                        4,
                        "DECISION",
                        "DECISION: Is the organizer cancelling the project? Includes a custom message emailed to signed-up volunteers",
                        List.of("CUR-047"),
                        "PARTIAL",
                        "DELETE /projects/{id}",
                        "DELETE /projects/{id} deletes a project after verifying org manager or regional admin permissions, but it does not accept a custom cancellation message or notify signed-up volunteers."
                ),
                new HarnessStep(
                        "4.5",
                        4,
                        "FAILURE_STATE",
                        "FAILURE STATE: Cancellation email must reliably reach every signed-up volunteer from an explicit event mapping",
                        List.of("ENH-045"),
                        "MISSING",
                        "Project Cancellation Notification (Not Implemented)",
                        "No event-to-recipient notification pipeline or email delivery service exists when a project or shift is cancelled."
                ),
                new HarnessStep(
                        "4.6",
                        4,
                        "STEP",
                        "Volunteers receive the message or cancellation notice and their sign-up status updates accordingly",
                        List.of("CUR-025", "CUR-047"),
                        "MISSING",
                        "GET /shift-signups (Not Implemented)",
                        "No notification delivery or automatic shift_signups status transition on project cancellation is implemented."
                )
        );
        return buildJourney(
                4,
                "Journey 4: Organization Communicates with Signed-Up Volunteers",
                "Tests shift-level volunteer rosters, organizer-to-volunteer messaging, and project cancellation notifications.",
                steps
        );
    }

    @Serdeable
    public record HarnessPersona(
            @Nullable String id,
            String email,
            String displayName,
            String systemRole,
            @Nullable String regionId,
            String regionName,
            @Nullable String regionRole,
            @Nullable String organizationId,
            String organizationName,
            @Nullable String organizationRole,
            boolean seededInDb,
            String description
    ) {}

    @Serdeable
    public record HarnessLoginRequest(
            String email,
            @Nullable String password
    ) {}

    @Serdeable
    public record HarnessLoginResponse(
            boolean authenticated,
            String email,
            @Nullable String idToken,
            @Nullable String accessToken,
            @Nullable String token,
            String message
    ) {}

    @Serdeable
    public record HarnessStep(
            String stepId,
            int journeyNumber,
            String stepType,
            String title,
            List<String> specRefs,
            String status,
            String endpoint,
            String honestAssessment
    ) {}

    @Serdeable
    public record HarnessJourney(
            int journeyNumber,
            String title,
            String summary,
            int totalSteps,
            int readyCount,
            int partialCount,
            int missingCount,
            List<HarnessStep> steps
    ) {}

    @Serdeable
    public record HarnessSpecReport(
            String specFile,
            int totalSteps,
            int readyCount,
            int partialCount,
            int missingCount,
            List<HarnessJourney> journeys
    ) {}
}
