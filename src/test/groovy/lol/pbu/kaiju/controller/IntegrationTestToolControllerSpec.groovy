package lol.pbu.kaiju.controller

import io.micronaut.core.io.ResourceResolver
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.controller.IntegrationTestToolController.LoginCommand
import lol.pbu.kaiju.controller.IntegrationTestToolController.LoginResult
import lol.pbu.kaiju.service.StubHttpClient
import lol.pbu.kaiju.service.StubHttpResponse
import spock.lang.Unroll

import java.net.http.HttpHeaders as JavaHttpHeaders
import java.net.http.HttpRequest as JavaHttpRequest
import java.net.http.HttpResponse as JavaHttpResponse

@MicronautTest(transactional = false)
class IntegrationTestToolControllerSpec extends BaseControllerSpec {

    @Inject
    ResourceResolver resourceResolver

    def "anonymous caller can view the integration test tool at root path"() {
        when: "an anonymous user navigates to the root endpoint"
        def response = client.exchange(HttpRequest.GET("/"), String)

        then: "the integration test tool is returned successfully as HTML"
        verifyAll {
            response.status == HttpStatus.OK
            response.contentType.isPresent()
            response.contentType.get().toString().contains(MediaType.TEXT_HTML)
            response.body().contains("Kaiju Integration Testing Tool")
            response.body().contains("Manual Test Harness - Not Production UI")
            response.body().contains("Volunteer Discovers and Commits")
            response.body().contains("Street Address")
            response.body().contains("Project Details")
        }
    }

    def "anonymous caller can view the integration test tool at test tool path"() {
        when: "an anonymous user navigates to the /integration-test-tool endpoint"
        def response = client.exchange(HttpRequest.GET("/integration-test-tool"), String)

        then: "the integration test tool is returned successfully as HTML"
        verifyAll {
            response.status == HttpStatus.OK
            response.contentType.isPresent()
            response.contentType.get().toString().contains(MediaType.TEXT_HTML)
            response.body().contains("Kaiju Integration Testing Tool")
            response.body().contains("Specialist Reviews and Approves")
        }
    }

    def "anonymous caller can view the integration test tool at dashboard alias path"() {
        when: "an anonymous user navigates to the /dashboard endpoint"
        def response = client.exchange(HttpRequest.GET("/dashboard"), String)

        then: "the integration test tool is returned successfully as HTML"
        verifyAll {
            response.status == HttpStatus.OK
            response.contentType.isPresent()
            response.contentType.get().toString().contains(MediaType.TEXT_HTML)
            response.body().contains("Organization Posts a Project")
        }
    }

    def "anonymous caller can list available test personas"() {
        when: "requesting the pre-configured personas list"
        def response = client.exchange(HttpRequest.GET("/integration-test-tool/api/personas"), String)

        then: "all 3 test personas are returned"
        verifyAll {
            response.status == HttpStatus.OK
            response.body().contains("Victor Volunteer")
            response.body().contains("Jenny Niu")
            response.body().contains("Daniel Director")
        }
    }

    def "login endpoint validates required username"() {
        when: "attempting login with an empty command"
        client.exchange(HttpRequest.POST("/integration-test-tool/api/login", new LoginCommand("", "")))

        then: "a bad request response is returned"
        def ex = thrown(HttpClientResponseException)
        verifyAll {
            ex.status == HttpStatus.BAD_REQUEST
        }
    }

    def "login rejects null command or blank username"() {
        given: "a controller instance"
        def controller = new IntegrationTestToolController(
                resourceResolver,
                "http://localhost:9000",
                "kaiju-client",
                "kaiju-secret",
                new StubHttpClient()
        )

        when: "calling login with invalid commands"
        def nullCmdResp = controller.login(null)
        def nullUserResp = controller.login(new LoginCommand(null, "password"))
        def blankUserResp = controller.login(new LoginCommand("   ", "password"))

        then: "bad request is returned for all cases"
        verifyAll {
            nullCmdResp.status == HttpStatus.BAD_REQUEST
            ((Map<?, ?>) nullCmdResp.body()).get("message") == "Username or email is required."
            nullUserResp.status == HttpStatus.BAD_REQUEST
            ((Map<?, ?>) nullUserResp.body()).get("message") == "Username or email is required."
            blankUserResp.status == HttpStatus.BAD_REQUEST
            ((Map<?, ?>) blankUserResp.body()).get("message") == "Username or email is required."
        }
    }

    def "successful login completes 5-step flow and maps user role and display name"() {
        given: "a stub client returning valid flow responses"
        StubHttpClient stubClient = createAuthentikStubClient(
                "http://localhost:8080/oauth/callback/authentik?code=mock-auth-code-123",
                '{"id_token": "mock-jwt-id-token", "access_token": "mock-jwt-access-token"}'
        )
        def controller = new IntegrationTestToolController(
                resourceResolver,
                "http://localhost:9000",
                "kaiju-client",
                "kaiju-secret",
                stubClient
        )

        when: "logging in with default and custom passwords"
        def defaultPwdResp = controller.login(new LoginCommand("victor.volunteer1@kaiju.local", null))
        def blankPwdResp = controller.login(new LoginCommand("victor.volunteer1@kaiju.local", "  "))
        def customPwdResp = controller.login(new LoginCommand('victor\\"special', "customSecret"))

        then: "all succeed with populated tokens"
        verifyAll {
            defaultPwdResp.status == HttpStatus.OK
            defaultPwdResp.body() instanceof LoginResult
            LoginResult defaultResult = (LoginResult) defaultPwdResp.body()
            defaultResult.idToken() == "mock-jwt-id-token"
            defaultResult.accessToken() == "mock-jwt-access-token"
            defaultResult.email() == "victor.volunteer1@kaiju.local"
            defaultResult.name() == "Victor Volunteer"
            defaultResult.role() == "STANDARD_USER"

            blankPwdResp.status == HttpStatus.OK
            customPwdResp.status == HttpStatus.OK
        }
    }

    @Unroll
    def "role and display name resolution matches expected persona mapping for #username"(
            String username,
            String expectedRole,
            String expectedName
    ) {
        given: "a stub client returning a valid token payload"
        StubHttpClient stubClient = createAuthentikStubClient(
                "http://localhost:8080/oauth/callback/authentik?code=sample-code",
                '{"id_token": "token-xyz", "access_token": "access-xyz"}'
        )
        def controller = new IntegrationTestToolController(
                resourceResolver,
                "http://localhost:9000",
                "kaiju-client",
                "kaiju-secret",
                stubClient
        )

        when: "logging in as the persona"
        def response = controller.login(new LoginCommand(username, "KaijuCommunity2026!"))

        then: "the mapped role and display name match expectations"
        verifyAll {
            response.status == HttpStatus.OK
            response.body() instanceof LoginResult
            LoginResult result = (LoginResult) response.body()
            result.role() == expectedRole
            result.name() == expectedName
            result.email() == username
        }

        where:
        username                        | expectedRole       | expectedName
        "daniel.director@kaiju.local"   | "REGION_DIRECTOR"  | "Daniel Director"
        "specialist.user@kaiju.local"   | "REGION_DIRECTOR"  | "specialist.user@kaiju.local"
        "jenny.niu@kaiju.local"         | "ORG_ADMIN"        | "Jenny Niu"
        "organizer.sam@kaiju.local"     | "ORG_ADMIN"        | "organizer.sam@kaiju.local"
        "admin.user@kaiju.local"        | "ORG_ADMIN"        | "admin.user@kaiju.local"
        "victor.volunteer@kaiju.local"  | "STANDARD_USER"    | "Victor Volunteer"
        "charlie.plain@kaiju.local"     | "STANDARD_USER"    | "charlie.plain@kaiju.local"
    }

    def "login returns unauthorized when authorization redirect lacks location header"() {
        given: "a stub client returning an authorization response without Location header"
        StubHttpClient stubClient = createAuthentikStubClient(null, '{"id_token": "token-1"}')
        def controller = new IntegrationTestToolController(
                resourceResolver,
                "http://localhost:9000",
                "kaiju-client",
                "kaiju-secret",
                stubClient
        )

        when: "attempting login"
        def response = controller.login(new LoginCommand("victor", "pass"))

        then: "unauthorized is returned"
        verifyAll {
            response.status == HttpStatus.UNAUTHORIZED
            ((Map<?, ?>) response.body()).get("message") == "Authentik authentication failed. Check user credentials."
        }
    }

    def "login returns unauthorized when authorization redirect lacks code parameter"() {
        given: "a stub client returning a redirect location without code"
        StubHttpClient stubClient = createAuthentikStubClient(
                "http://localhost:8080/oauth/callback/authentik?error=access_denied",
                '{"id_token": "token-1"}'
        )
        def controller = new IntegrationTestToolController(
                resourceResolver,
                "http://localhost:9000",
                "kaiju-client",
                "kaiju-secret",
                stubClient
        )

        when: "attempting login"
        def response = controller.login(new LoginCommand("victor", "pass"))

        then: "unauthorized is returned"
        verifyAll {
            response.status == HttpStatus.UNAUTHORIZED
            ((Map<?, ?>) response.body()).get("message") == "Authentik authentication failed. Check user credentials."
        }
    }

    def "login returns unauthorized when token response lacks id token"() {
        given: "a stub client returning token response without id_token"
        StubHttpClient stubClient = createAuthentikStubClient(
                "http://localhost:8080/oauth/callback/authentik?code=valid-code",
                '{"access_token": "token-without-id"}'
        )
        def controller = new IntegrationTestToolController(
                resourceResolver,
                "http://localhost:9000",
                "kaiju-client",
                "kaiju-secret",
                stubClient
        )

        when: "attempting login"
        def response = controller.login(new LoginCommand("victor", "pass"))

        then: "unauthorized is returned"
        verifyAll {
            response.status == HttpStatus.UNAUTHORIZED
            ((Map<?, ?>) response.body()).get("message") == "Authentik authentication failed. Check user credentials."
        }
    }

    def "login returns service unavailable on network io failure"() {
        given: "a stub client throwing IOException"
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { JavaHttpRequest req, JavaHttpResponse.BodyHandler<?> handler ->
            throw new IOException("Connection refused by IdP")
        })
        def controller = new IntegrationTestToolController(
                resourceResolver,
                "http://localhost:9000",
                "kaiju-client",
                "kaiju-secret",
                stubClient
        )

        when: "attempting login"
        def response = controller.login(new LoginCommand("victor", "pass"))

        then: "service unavailable is returned with IdP error message"
        verifyAll {
            response.status == HttpStatus.SERVICE_UNAVAILABLE
            ((Map<?, ?>) response.body()).get("message") == "Authentik IdP is unavailable: Connection refused by IdP"
        }
    }

    def "login returns service unavailable and restores interrupt flag on interrupted exception"() {
        given: "a stub client throwing InterruptedException"
        StubHttpClient stubClient = new StubHttpClient(sendHandler: { JavaHttpRequest req, JavaHttpResponse.BodyHandler<?> handler ->
            throw new InterruptedException("Thread interrupted during login")
        })
        def controller = new IntegrationTestToolController(
                resourceResolver,
                "http://localhost:9000",
                "kaiju-client",
                "kaiju-secret",
                stubClient
        )

        when: "attempting login"
        def response = controller.login(new LoginCommand("victor", "pass"))
        boolean threadWasInterrupted = Thread.interrupted()

        then: "service unavailable is returned and interrupt flag was re-asserted"
        verifyAll {
            response.status == HttpStatus.SERVICE_UNAVAILABLE
            ((Map<?, ?>) response.body()).get("message") == "Authentik login was interrupted."
            threadWasInterrupted
        }
    }

    def "tool loads fallback html when resource stream throws io error"() {
        given: "a resource resolver with an unreadable stream"
        CustomResourceResolver mockResolver = new CustomResourceResolver(new FailingInputStream())

        when: "creating the controller"
        def controller = new IntegrationTestToolController(
                mockResolver,
                "http://localhost:9000",
                "kaiju-client",
                "kaiju-secret"
        )

        then: "fallback error html is returned"
        verifyAll {
            controller.root().contains("Error loading Integration Testing Tool")
        }
    }

    def "tool loads fallback html when resource is missing"() {
        given: "a resource resolver without the template resource"
        CustomResourceResolver mockResolver = new CustomResourceResolver(null)

        when: "creating the controller"
        def controller = new IntegrationTestToolController(
                mockResolver,
                "http://localhost:9000",
                "kaiju-client",
                "kaiju-secret"
        )

        then: "fallback missing resource html is returned"
        verifyAll {
            controller.root().contains("Tooling resource not found.")
        }
    }

    def "controller initializes default http client when client is omitted"() {
        when: "creating controller without custom http client"
        def controller = new IntegrationTestToolController(
                resourceResolver,
                "http://localhost:9000",
                "kaiju-client",
                "kaiju-secret"
        )

        then: "controller initializes successfully with rendered html content"
        verifyAll {
            controller != null
            controller.root().contains("Kaiju Integration Testing Tool")
        }
    }

    private static StubHttpClient createAuthentikStubClient(String locationHeader, String tokenBody) {
        return new StubHttpClient(sendHandler: { JavaHttpRequest req, JavaHttpResponse.BodyHandler<?> handler ->
            String uriStr = req.uri().toString()
            if (uriStr.contains("/application/o/authorize/")) {
                JavaHttpHeaders headers = locationHeader != null
                        ? JavaHttpHeaders.of(["Location": [locationHeader]], { String k, String v -> true })
                        : JavaHttpHeaders.of([:], { String k, String v -> true })
                return new StubAuthentikResponse<String>(302, "", headers)
            } else if (uriStr.contains("/application/o/token/")) {
                return new StubAuthentikResponse<String>(200, tokenBody)
            } else {
                return new StubAuthentikResponse<Void>(200, null)
            }
        })
    }

    static class StubAuthentikResponse<T> extends StubHttpResponse<T> {
        private final JavaHttpHeaders httpHeaders

        StubAuthentikResponse(int code, T body, JavaHttpHeaders httpHeaders = null) {
            super(code, body)
            this.httpHeaders = httpHeaders
        }

        @Override
        JavaHttpHeaders headers() {
            return httpHeaders != null ? httpHeaders : JavaHttpHeaders.of([:], { String k, String v -> true })
        }
    }

    static class FailingInputStream extends InputStream {
        @Override
        int read() {
            throw new IOException("Simulated disk read error")
        }

        @Override
        byte[] readAllBytes() {
            throw new IOException("Simulated disk read error")
        }
    }

    static class CustomResourceResolver extends ResourceResolver {
        private final InputStream stream

        CustomResourceResolver(InputStream stream) {
            this.stream = stream
        }

        @Override
        Optional<InputStream> getResourceAsStream(String name) {
            return Optional.ofNullable(stream)
        }
    }
}
