package lol.pbu.kaiju.fixtures

import com.nimbusds.jose.util.JSONObjectUtils
import io.micronaut.http.HttpRequest
import io.micronaut.http.cookie.Cookie

import java.net.CookieHandler
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient as JavaHttpClient
import java.net.http.HttpRequest as JavaHttpRequest
import java.net.http.HttpResponse as JavaHttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class AuthentikTestDriver {

    private final String baseUrl
    private final String issuer
    private final String jwksUrl
    private final String clientId
    private final String clientSecret
    private final String defaultRedirectUri

    AuthentikTestDriver(
            String baseUrl = "http://localhost:9000",
            String issuer = "http://localhost:9000/application/o/kaiju/",
            String jwksUrl = "http://localhost:9000/application/o/kaiju/jwks/",
            String clientId = "kaiju-client",
            String clientSecret = "kaiju-secret",
            String defaultRedirectUri = "http://localhost:8080/oauth/callback/authentik"
    ) {
        this.baseUrl = baseUrl
        this.issuer = issuer
        this.jwksUrl = jwksUrl
        this.clientId = clientId
        this.clientSecret = clientSecret
        this.defaultRedirectUri = defaultRedirectUri
    }

    String getBaseUrl() {
        return baseUrl
    }

    String getIssuer() {
        return issuer
    }

    String getJwksUrl() {
        return jwksUrl
    }

    String getClientId() {
        return clientId
    }

    String getClientSecret() {
        return clientSecret
    }

    String getDefaultRedirectUri() {
        return defaultRedirectUri
    }

    CookieHandler createCookieJar(Map<String, String> cookies = [:]) {
        return new CookieHandler() {
            @Override
            Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders) {
                Map<String, List<String>> map = [:]
                if (!cookies.isEmpty()) {
                    String cookieStr = cookies.collect { k, v -> "${k}=${v}" }.join("; ")
                    map.put("Cookie", [cookieStr])
                }
                return map
            }

            @Override
            void put(URI uri, Map<String, List<String>> responseHeaders) {
                List<String> setCookieHeaders = responseHeaders.find { k, v -> "Set-Cookie".equalsIgnoreCase(k) }?.value
                if (setCookieHeaders == null) {
                    return
                }
                setCookieHeaders.each { header ->
                    parseCookieHeader(header, cookies)
                }
            }
        }
    }

    private static void parseCookieHeader(String header, Map<String, String> cookies) {
        String[] parts = header.split(";")
        String[] nameValue = parts[0].split("=", 2)
        if (nameValue.length != 2) {
            return
        }
        String name = nameValue[0].trim()
        String value = nameValue[1].trim()
        boolean isExpired = parts.any { part ->
            String trimmed = part.trim().toLowerCase(Locale.ROOT)
            trimmed == "max-age=0" || trimmed.startsWith("max-age=-")
        }
        if (isExpired || value.isEmpty() || value == "\"\"") {
            cookies.remove(name)
        } else {
            cookies[name] = value
        }
    }

    void loginUserToAuthentik(CookieHandler handler, String username, String password = "KaijuCommunity2026!") {
        JavaHttpClient client = JavaHttpClient.newBuilder()
                .version(JavaHttpClient.Version.HTTP_1_1)
                .followRedirects(JavaHttpClient.Redirect.NORMAL)
                .cookieHandler(handler)
                .connectTimeout(Duration.ofSeconds(5))
                .build()

        JavaHttpRequest initReq = JavaHttpRequest.newBuilder()
                .uri(URI.create("${baseUrl}/api/v3/flows/executor/default-authentication-flow/?query="))
                .GET()
                .build()
        client.send(initReq, JavaHttpResponse.BodyHandlers.ofString())

        String idPayload = """{"component": "ak-stage-identification", "uid_field": "${username}"}"""
        JavaHttpRequest idReq = JavaHttpRequest.newBuilder()
                .uri(URI.create("${baseUrl}/api/v3/flows/executor/default-authentication-flow/?query="))
                .header("Content-Type", "application/json")
                .POST(JavaHttpRequest.BodyPublishers.ofString(idPayload))
                .build()
        client.send(idReq, JavaHttpResponse.BodyHandlers.ofString())

        String passPayload = """{"component": "ak-stage-password", "password": "${password}"}"""
        JavaHttpRequest passReq = JavaHttpRequest.newBuilder()
                .uri(URI.create("${baseUrl}/api/v3/flows/executor/default-authentication-flow/?query="))
                .header("Content-Type", "application/json")
                .POST(JavaHttpRequest.BodyPublishers.ofString(passPayload))
                .build()
        client.send(passReq, JavaHttpResponse.BodyHandlers.ofString())
    }

    Map<String, String> obtainLiveTokens(String username, String password = "KaijuCommunity2026!") {
        Map<String, String> cookies = [:]
        CookieHandler handler = createCookieJar(cookies)

        loginUserToAuthentik(handler, username, password)

        JavaHttpClient authClient = JavaHttpClient.newBuilder()
                .version(JavaHttpClient.Version.HTTP_1_1)
                .followRedirects(JavaHttpClient.Redirect.NEVER)
                .cookieHandler(handler)
                .connectTimeout(Duration.ofSeconds(5))
                .build()

        String authUrl = "${baseUrl}/application/o/authorize/?" +
                "client_id=${URLEncoder.encode(clientId, StandardCharsets.UTF_8)}&" +
                "response_type=code&" +
                "redirect_uri=${URLEncoder.encode(defaultRedirectUri, StandardCharsets.UTF_8)}&" +
                "scope=openid%20email%20profile"
        JavaHttpRequest authReq = JavaHttpRequest.newBuilder()
                .uri(URI.create(authUrl))
                .GET()
                .build()
        JavaHttpResponse<String> authResp = authClient.send(authReq, JavaHttpResponse.BodyHandlers.ofString())

        String redirectLocation = authResp.headers().firstValue("Location").orElse(null)
        if (redirectLocation == null) {
            throw new IllegalStateException("Failed to obtain redirect from Authentik authorization endpoint. Status: ${authResp.statusCode()}, Body: ${authResp.body()}")
        }

        URI redirectUri = URI.create(redirectLocation)
        String query = redirectUri.getQuery()
        String code = null
        if (query != null) {
            code = query.split("&")
                    .collect { it.split("=") }
                    .find { it.length == 2 && it[0] == "code" }
                    ?.with { URLDecoder.decode(it[1], StandardCharsets.UTF_8) }
        }
        if (code == null) {
            throw new IllegalStateException("Authorization code not found in redirect URL: ${redirectLocation}")
        }

        String tokenBody = "grant_type=authorization_code&" +
                "client_id=${URLEncoder.encode(clientId, StandardCharsets.UTF_8)}&" +
                "client_secret=${URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)}&" +
                "redirect_uri=${URLEncoder.encode(defaultRedirectUri, StandardCharsets.UTF_8)}&" +
                "code=${URLEncoder.encode(code, StandardCharsets.UTF_8)}"
        JavaHttpRequest tokenReq = JavaHttpRequest.newBuilder()
                .uri(URI.create("${baseUrl}/application/o/token/"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(JavaHttpRequest.BodyPublishers.ofString(tokenBody))
                .build()
        JavaHttpResponse<String> tokenResp = authClient.send(tokenReq, JavaHttpResponse.BodyHandlers.ofString())

        Map<String, Object> tokenJson = JSONObjectUtils.parse(tokenResp.body())
        return [
                idToken: tokenJson.get("id_token") as String,
                accessToken: tokenJson.get("access_token") as String
        ]
    }

    OAuthLoginResult executeBrowserOAuthLogin(int serverPort, String username, String password = "KaijuCommunity2026!") {
        Map<String, String> cookies = [:]
        CookieHandler handler = createCookieJar(cookies)
        JavaHttpClient browser = JavaHttpClient.newBuilder()
                .version(JavaHttpClient.Version.HTTP_1_1)
                .followRedirects(JavaHttpClient.Redirect.NEVER)
                .cookieHandler(handler)
                .connectTimeout(Duration.ofSeconds(5))
                .build()

        loginUserToAuthentik(handler, username, password)

        JavaHttpRequest loginReq = JavaHttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${serverPort}/oauth/login/authentik"))
                .GET()
                .build()
        JavaHttpResponse<String> loginResp = browser.send(loginReq, JavaHttpResponse.BodyHandlers.ofString())

        Map<String, String> loginCookies = new LinkedHashMap<>(cookies)

        String authorizeUrl = loginResp.headers().firstValue("Location").orElse(null)
        if (authorizeUrl == null) {
            throw new IllegalStateException("No Location header in login response: ${loginResp.statusCode()}")
        }

        JavaHttpRequest authReq = JavaHttpRequest.newBuilder()
                .uri(URI.create(authorizeUrl))
                .GET()
                .build()
        JavaHttpResponse<String> authResp = browser.send(authReq, JavaHttpResponse.BodyHandlers.ofString())

        String callbackUrl = authResp.headers().firstValue("Location").orElse(null)
        if (callbackUrl == null) {
            throw new IllegalStateException("No Location header in auth response: ${authResp.statusCode()}")
        }

        JavaHttpRequest callbackReq = JavaHttpRequest.newBuilder()
                .uri(URI.create(callbackUrl))
                .GET()
                .build()
        JavaHttpResponse<String> callbackResp = browser.send(callbackReq, JavaHttpResponse.BodyHandlers.ofString())

        return new OAuthLoginResult(
                loginResp.statusCode(),
                authorizeUrl,
                loginCookies,
                authResp.statusCode(),
                callbackUrl,
                callbackResp.statusCode(),
                callbackResp.headers().firstValue("Location").orElse(null),
                cookies,
                browser
        )
    }

    static class OAuthLoginResult {
        final int loginStatus
        final String authorizeUrl
        final Map<String, String> loginCookies
        final int authStatus
        final String callbackUrl
        final int callbackStatus
        final String location
        final Map<String, String> cookies
        final JavaHttpClient browser

        OAuthLoginResult(
                int loginStatus,
                String authorizeUrl,
                Map<String, String> loginCookies,
                int authStatus,
                String callbackUrl,
                int callbackStatus,
                String location,
                Map<String, String> cookies,
                JavaHttpClient browser
        ) {
            this.loginStatus = loginStatus
            this.authorizeUrl = authorizeUrl
            this.loginCookies = loginCookies
            this.authStatus = authStatus
            this.callbackUrl = callbackUrl
            this.callbackStatus = callbackStatus
            this.location = location
            this.cookies = cookies
            this.browser = browser
        }

        boolean hasSecurityCookies() {
            return loginCookies.containsKey("OAUTH2_STATE") &&
                    loginCookies.containsKey("OAUTH2_PKCE") &&
                    loginCookies.containsKey("OPENID_NONCE")
        }

        boolean getHasSecurityCookies() {
            return hasSecurityCookies()
        }

        String getJwtCookie() {
            return cookies.get("JWT")
        }

        boolean isRedirected() {
            return callbackStatus == 303 || callbackStatus == 302
        }

        boolean getRedirected() {
            return isRedirected()
        }

        boolean hasJwtCookie() {
            String jwt = getJwtCookie()
            return jwt != null && !jwt.isBlank()
        }

        boolean getHasJwtCookie() {
            return hasJwtCookie()
        }

        JavaHttpClient authenticatedClient() {
            return browser
        }

        HttpRequest<?> applyJwtCookie(HttpRequest<?> request) {
            String jwt = getJwtCookie()
            if (jwt != null) {
                return request.cookie(Cookie.of("JWT", jwt))
            }
            return request
        }
    }
}
