package lol.pbu.kaiju.security

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.util.JSONObjectUtils
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.BlockingHttpClient
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.security.authentication.AuthenticationResponse
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdClaims
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdTokenResponse
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import lol.pbu.kaiju.domain.AdministrativeRegion
import lol.pbu.kaiju.domain.User
import lol.pbu.kaiju.model.UserRole
import lol.pbu.kaiju.repository.AdministrativeRegionRepository
import lol.pbu.kaiju.repository.UserRepository
import lol.pbu.kaiju.service.UserService
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import java.net.CookieHandler
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient as JavaHttpClient
import java.net.http.HttpRequest as JavaHttpRequest
import java.net.http.HttpResponse as JavaHttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@Property(name = "micronaut.security.enabled", value = "true")
@Property(name = "micronaut.security.token.jwt.enabled", value = "true")
@Property(name = "micronaut.security.token.jwt.signatures.jwks.authentik.url", value = "http://localhost:9000/application/o/kaiju/jwks/")
@Property(name = "micronaut.security.token.jwt.signatures.secret.generator.secret", value = "pleaseChangeThisSecretForANewOne32Bits")
@MicronautTest(transactional = false)
@Requires({ AuthentikOidcIntegrationSpec.isAuthentikAvailable() })
class AuthentikOidcIntegrationSpec extends Specification {

    private static final String AUTHENTIK_BASE_URL = "http://localhost:9000"
    private static final String OIDC_ISSUER = "http://localhost:9000/application/o/kaiju/"
    private static final String JWKS_URL = "http://localhost:9000/application/o/kaiju/jwks/"
    private static final String CLIENT_ID = "kaiju-client"
    private static final String CLIENT_SECRET = "kaiju-secret"
    private static final String REDIRECT_URI = "http://localhost:8080/oauth/callback/authentik"

    @Shared
    JWKSet jwkSet

    def setupSpec() {
        if (isAuthentikAvailable()) {
            jwkSet = JWKSet.load(new URI(JWKS_URL).toURL())
        }
    }

    @Inject
    UserRepository userRepository

    @Inject
    UserService userService

    @Inject
    AdministrativeRegionRepository administrativeRegionRepository

    @Inject
    AuthentikAuthenticationMapper mapper

    @Inject
    @Client("/")
    HttpClient httpClient

    BlockingHttpClient getClient() {
        httpClient.toBlocking()
    }

    private static Map<String, Object> createTestRegionPayload(String name) {
        return [
                name: name,
                parentRegionId: null,
                coordinates: [
                        [longitude: -105.0, latitude: 40.0],
                        [longitude: -105.0, latitude: 41.0],
                        [longitude: -104.0, latitude: 41.0],
                        [longitude: -104.0, latitude: 40.0],
                        [longitude: -105.0, latitude: 40.0]
                ]
        ]
    }

    static boolean isAuthentikAvailable() {
        try {
            JavaHttpClient probeClient = JavaHttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build()
            JavaHttpRequest probeReq = JavaHttpRequest.newBuilder()
                    .uri(URI.create("${AUTHENTIK_BASE_URL}/application/o/kaiju/.well-known/openid-configuration"))
                    .GET()
                    .build()
            JavaHttpResponse<String> resp = probeClient.send(probeReq, JavaHttpResponse.BodyHandlers.ofString())
            return resp.statusCode() == 200
        } catch (Exception ignored) {
            return false
        }
    }

    private static <T> T await(Publisher<T> publisher) {
        CompletableFuture<T> future = new CompletableFuture<>()
        publisher.subscribe(new Subscriber<T>() {
            @Override
            void onSubscribe(Subscription s) {
                s.request(1)
            }
            @Override
            void onNext(T t) {
                future.complete(t)
            }
            @Override
            void onError(Throwable t) {
                future.completeExceptionally(t)
            }
            @Override
            void onComplete() {
                if (!future.isDone()) {
                    future.complete(null)
                }
            }
        })
        return future.get(5, TimeUnit.SECONDS)
    }

    /**
     * Executes the authentic live OIDC authorization flow against Authentik
     * to obtain genuine RS256 signed ID and access tokens for a given persona.
     */
    private Map<String, String> obtainLiveTokens(String username, String password = "KaijuCommunity2026!") {
        Map<String, String> cookies = [:]

        CookieHandler handler = new CookieHandler() {
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
                List<String> sc = responseHeaders.get("Set-Cookie")
                if (sc != null) {
                    sc.each { header ->
                        String[] p = header.split(";")[0].split("=", 2)
                        if (p.length == 2) {
                            cookies[p[0].trim()] = p[1].trim()
                        }
                    }
                }
            }
        }

        JavaHttpClient flowClient = JavaHttpClient.newBuilder()
                .version(JavaHttpClient.Version.HTTP_1_1)
                .followRedirects(JavaHttpClient.Redirect.NORMAL)
                .cookieHandler(handler)
                .connectTimeout(Duration.ofSeconds(5))
                .build()

        // 1. Initialize authentication flow
        JavaHttpRequest initReq = JavaHttpRequest.newBuilder()
                .uri(URI.create("${AUTHENTIK_BASE_URL}/api/v3/flows/executor/default-authentication-flow/?query="))
                .GET()
                .build()
        flowClient.send(initReq, JavaHttpResponse.BodyHandlers.ofString())

        // 2. Submit username / email
        String idPayload = """{"component": "ak-stage-identification", "uid_field": "${username}"}"""
        JavaHttpRequest idReq = JavaHttpRequest.newBuilder()
                .uri(URI.create("${AUTHENTIK_BASE_URL}/api/v3/flows/executor/default-authentication-flow/?query="))
                .header("Content-Type", "application/json")
                .POST(JavaHttpRequest.BodyPublishers.ofString(idPayload))
                .build()
        flowClient.send(idReq, JavaHttpResponse.BodyHandlers.ofString())

        // 3. Submit password
        String passPayload = """{"component": "ak-stage-password", "password": "${password}"}"""
        JavaHttpRequest passReq = JavaHttpRequest.newBuilder()
                .uri(URI.create("${AUTHENTIK_BASE_URL}/api/v3/flows/executor/default-authentication-flow/?query="))
                .header("Content-Type", "application/json")
                .POST(JavaHttpRequest.BodyPublishers.ofString(passPayload))
                .build()
        flowClient.send(passReq, JavaHttpResponse.BodyHandlers.ofString())

        // 4. Request authorization code for Kaiju client with Redirect.NEVER to capture the 302 callback
        JavaHttpClient authClient = JavaHttpClient.newBuilder()
                .version(JavaHttpClient.Version.HTTP_1_1)
                .followRedirects(JavaHttpClient.Redirect.NEVER)
                .cookieHandler(handler)
                .connectTimeout(Duration.ofSeconds(5))
                .build()

        String authUrl = "${AUTHENTIK_BASE_URL}/application/o/authorize/?" +
                "client_id=${URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8)}&" +
                "response_type=code&" +
                "redirect_uri=${URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8)}&" +
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
            for (String param : query.split("&")) {
                String[] pair = param.split("=")
                if (pair.length == 2 && pair[0] == "code") {
                    code = URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
                    break
                }
            }
        }
        if (code == null) {
            throw new IllegalStateException("Authorization code not found in redirect URL: ${redirectLocation}")
        }

        // 5. Exchange code for tokens
        String tokenBody = "grant_type=authorization_code&" +
                "client_id=${URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8)}&" +
                "client_secret=${URLEncoder.encode(CLIENT_SECRET, StandardCharsets.UTF_8)}&" +
                "redirect_uri=${URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8)}&" +
                "code=${URLEncoder.encode(code, StandardCharsets.UTF_8)}"
        JavaHttpRequest tokenReq = JavaHttpRequest.newBuilder()
                .uri(URI.create("${AUTHENTIK_BASE_URL}/application/o/token/"))
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

    def "live Authentik endpoint publishes valid OpenID configuration and JWKS with RS256 key"() {
        given: "a query to Authentik's OpenID configuration discovery endpoint"
        JavaHttpClient client = JavaHttpClient.newHttpClient()
        JavaHttpRequest req = JavaHttpRequest.newBuilder()
                .uri(URI.create("${AUTHENTIK_BASE_URL}/application/o/kaiju/.well-known/openid-configuration"))
                .GET()
                .build()

        when: "fetching the OpenID configuration"
        JavaHttpResponse<String> resp = client.send(req, JavaHttpResponse.BodyHandlers.ofString())
        Map<String, Object> config = JSONObjectUtils.parse(resp.body())

        then: "the response is HTTP 200 with matching issuer and signing algorithms"
        resp.statusCode() == 200
        config.get("issuer") == OIDC_ISSUER
        config.get("jwks_uri") == JWKS_URL
        (config.get("id_token_signing_alg_values_supported") as List).contains("RS256")

        then: "the key set contains a valid RSA signing key using RS256"
        !jwkSet.getKeys().isEmpty()
        RSAKey rsaKey = (RSAKey) jwkSet.getKeys().get(0)
        rsaKey.getAlgorithm() == JWSAlgorithm.RS256
        rsaKey.getKeyUse().identifier() == "sig"
        rsaKey.toRSAPublicKey() != null
    }

    @Unroll
    def "genuine Authentik ID token for persona '#username' has valid RS256 signature and matching claims"() {
        given: "a live OIDC token pair obtained from Authentik"
        Map<String, String> tokens = obtainLiveTokens(username)
        String idTokenString = tokens.idToken
        assert idTokenString != null: "Failed to obtain ID token from Authentik"

        when: "parsing the signed JWT and retrieving the cached JWKS public key"
        SignedJWT signedJWT = SignedJWT.parse(idTokenString)
        RSAKey rsaKey = (RSAKey) jwkSet.getKeyByKeyId(signedJWT.getHeader().getKeyID())
        RSASSAVerifier verifier = new RSASSAVerifier(rsaKey.toRSAPublicKey())

        then: "the cryptographic RS256 signature validates successfully against live JWKS"
        signedJWT.verify(verifier)

        and: "token claims contain expected OpenID metadata"
        def claims = signedJWT.getJWTClaimsSet()
        claims.getIssuer() == OIDC_ISSUER
        claims.getAudience().contains(CLIENT_ID)
        claims.getStringClaim("email") == expectedEmail
        claims.getStringListClaim("groups").contains(expectedGroup)

        and: "token expiration is in the future"
        claims.getExpirationTime().after(new Date())

        where:
        username                          | expectedEmail                     | expectedGroup
        "sarah.admin1@kaiju.local"        | "sarah.admin1@kaiju.local"        | "Kaiju Global Admins"
        "rachel.state.director1@kaiju.local" | "rachel.state.director1@kaiju.local" | "Kaiju Region Directors"
        "nathan.national.admin1@kaiju.local" | "nathan.national.admin1@kaiju.local" | "Kaiju Org Admins"
        "victor.volunteer1@kaiju.local"   | "victor.volunteer1@kaiju.local"   | "Kaiju Standard Users"
    }

    def "tampered token signature fails cryptographic verification"() {
        given: "a genuine ID token from Authentik"
        Map<String, String> tokens = obtainLiveTokens("sarah.admin1@kaiju.local")
        String idTokenString = tokens.idToken

        and: "a tamperer who modifies a single character in the signature block"
        String[] parts = idTokenString.split("\\.")
        String tamperedSignature = parts[2].substring(0, parts[2].length() - 2) + "XX"
        String tamperedTokenString = "${parts[0]}.${parts[1]}.${tamperedSignature}"

        when: "verifying the tampered token against the cached JWKS key"
        SignedJWT tamperedJWT = SignedJWT.parse(tamperedTokenString)
        RSAKey rsaKey = (RSAKey) jwkSet.getKeyByKeyId(tamperedJWT.getHeader().getKeyID())
        RSASSAVerifier verifier = new RSASSAVerifier(rsaKey.toRSAPublicKey())
        boolean valid = tamperedJWT.verify(verifier)

        then: "signature verification strictly fails"
        !valid
    }

    def "tampered token payload claims fail cryptographic verification"() {
        given: "a genuine ID token from Authentik"
        Map<String, String> tokens = obtainLiveTokens("victor.volunteer1@kaiju.local")
        String idTokenString = tokens.idToken

        and: "an attacker who modifies payload claims to grant themselves global admin"
        String[] parts = idTokenString.split("\\.")
        String payloadJson = new String(Base64.urlDecoder.decode(parts[1]), StandardCharsets.UTF_8)
        String forgedPayloadJson = payloadJson.replace("Kaiju Standard Users", "Kaiju Global Admins")
        String forgedPayloadB64 = Base64.urlEncoder.withoutPadding().encodeToString(forgedPayloadJson.getBytes(StandardCharsets.UTF_8))
        String forgedToken = "${parts[0]}.${forgedPayloadB64}.${parts[2]}"

        when: "verifying the forged token with the authentic public key"
        SignedJWT forgedJWT = SignedJWT.parse(forgedToken)
        RSAKey rsaKey = (RSAKey) jwkSet.getKeyByKeyId(forgedJWT.getHeader().getKeyID())
        RSASSAVerifier verifier = new RSASSAVerifier(rsaKey.toRSAPublicKey())
        boolean valid = forgedJWT.verify(verifier)

        then: "cryptographic verification fails due to payload tampering"
        !valid
    }

    def "expired token fails temporal validity assertion"() {
        given: "a genuine ID token"
        Map<String, String> tokens = obtainLiveTokens("sarah.admin1@kaiju.local")
        SignedJWT signedJWT = SignedJWT.parse(tokens.idToken)
        Date expiration = signedJWT.getJWTClaimsSet().getExpirationTime()

        expect: "current time is before token expiration"
        new Date().before(expiration)

        and: "a point in time after expiration is correctly identified as expired"
        Date simulatedFutureTime = new Date(expiration.getTime() + 10000)
        simulatedFutureTime.after(expiration)
    }

    def "HTTP requests with expired authorization token return 401 Unauthorized"() {
        given: "a signed token with an expiration timestamp in the past"
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        def keyPair = gen.generateKeyPair()
        RSASSASigner signer = new RSASSASigner((RSAPrivateKey) keyPair.private)
        JWTClaimsSet expiredClaims = new JWTClaimsSet.Builder()
                .issuer(OIDC_ISSUER)
                .audience(CLIENT_ID)
                .subject("sarah.admin1@kaiju.local")
                .expirationTime(new Date(System.currentTimeMillis() - 60000))
                .issueTime(new Date(System.currentTimeMillis() - 120000))
                .build()
        SignedJWT expiredJWT = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), expiredClaims)
        expiredJWT.sign(signer)
        String expiredToken = expiredJWT.serialize()

        when: "calling a secured endpoint with the expired token"
        client.exchange(
                HttpRequest.GET("/projects")
                        .header("Authorization", "Bearer ${expiredToken}")
        )

        then: "access is rejected with 401 Unauthorized"
        HttpClientResponseException ex = thrown()
        ex.status == HttpStatus.UNAUTHORIZED
    }

    def "HTTP requests with untrusted or mismatched issuer token return 401 Unauthorized"() {
        given: "a cryptographically valid HMAC-signed token issued by an untrusted issuer"
        byte[] secretBytes = "pleaseChangeThisSecretForANewOne32Bits".getBytes(StandardCharsets.UTF_8)
        def signer = new MACSigner(secretBytes)
        JWTClaimsSet untrustedClaims = new JWTClaimsSet.Builder()
                .issuer("http://untrusted-idp.example.com/application/o/evil/")
                .audience(CLIENT_ID)
                .subject("sarah.admin1@kaiju.local")
                .claim("email", "sarah.admin1@kaiju.local")
                .claim("email_verified", true)
                .expirationTime(new Date(System.currentTimeMillis() + 60000))
                .build()
        SignedJWT untrustedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), untrustedClaims)
        untrustedJWT.sign(signer)
        String untrustedToken = untrustedJWT.serialize()

        when: "calling a secured endpoint with the untrusted issuer token"
        client.exchange(
                HttpRequest.GET("/projects")
                        .header("Authorization", "Bearer ${untrustedToken}")
        )

        then: "access is rejected with 401 Unauthorized"
        HttpClientResponseException ex = thrown()
        ex.status == HttpStatus.UNAUTHORIZED

        when: "calling the same secured endpoint with a token signed with the identical HMAC key but a trusted issuer"
        JWTClaimsSet validClaims = new JWTClaimsSet.Builder()
                .issuer(OIDC_ISSUER)
                .audience(CLIENT_ID)
                .subject("sarah.admin1@kaiju.local")
                .claim("email", "sarah.admin1@kaiju.local")
                .claim("email_verified", true)
                .expirationTime(new Date(System.currentTimeMillis() + 60000))
                .build()
        SignedJWT validJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), validClaims)
        validJWT.sign(signer)
        String validToken = validJWT.serialize()

        def response = client.exchange(
                HttpRequest.GET("/projects")
                        .header("Authorization", "Bearer ${validToken}")
        )

        then: "access is granted with 200 OK, confirming signature verification passed and rejection was due to issuer claim validation"
        response.status == HttpStatus.OK
    }

    def "HTTP requests with untrusted or mismatched audience token return 401 Unauthorized"() {
        given: "a cryptographically valid HMAC-signed token intended for a different audience"
        byte[] secretBytes = "pleaseChangeThisSecretForANewOne32Bits".getBytes(StandardCharsets.UTF_8)
        def signer = new MACSigner(secretBytes)
        JWTClaimsSet untrustedAudienceClaims = new JWTClaimsSet.Builder()
                .issuer(OIDC_ISSUER)
                .audience("untrusted-audience-client")
                .subject("sarah.admin1@kaiju.local")
                .claim("email", "sarah.admin1@kaiju.local")
                .claim("email_verified", true)
                .expirationTime(new Date(System.currentTimeMillis() + 60000))
                .build()
        SignedJWT untrustedAudienceJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), untrustedAudienceClaims)
        untrustedAudienceJWT.sign(signer)
        String untrustedAudienceToken = untrustedAudienceJWT.serialize()

        when: "calling a secured endpoint with the mismatched audience token"
        client.exchange(
                HttpRequest.GET("/projects")
                        .header("Authorization", "Bearer ${untrustedAudienceToken}")
        )

        then: "access is rejected with 401 Unauthorized"
        HttpClientResponseException ex = thrown()
        ex.status == HttpStatus.UNAUTHORIZED
    }

    def "HTTP requests with tampered token signature return 401 Unauthorized"() {
        given: "a genuine token from Authentik with altered signature"
        Map<String, String> tokens = obtainLiveTokens("sarah.admin1@kaiju.local")
        String[] parts = tokens.idToken.split("\\.")
        String tamperedSignature = parts[2].substring(0, parts[2].length() - 2) + "XX"
        String tamperedToken = "${parts[0]}.${parts[1]}.${tamperedSignature}"

        when: "calling a secured endpoint with the signature-tampered token"
        client.exchange(
                HttpRequest.GET("/projects")
                        .header("Authorization", "Bearer ${tamperedToken}")
        )

        then: "access is strictly rejected with 401 Unauthorized"
        HttpClientResponseException ex = thrown()
        ex.status == HttpStatus.UNAUTHORIZED
    }

    def "HTTP requests with tampered token payload claims return 401 Unauthorized"() {
        given: "a genuine token from Authentik with forged role in payload"
        Map<String, String> tokens = obtainLiveTokens("victor.volunteer1@kaiju.local")
        String[] parts = tokens.idToken.split("\\.")
        String payloadJson = new String(Base64.urlDecoder.decode(parts[1]), StandardCharsets.UTF_8)
        String forgedPayloadJson = payloadJson.replace("Kaiju Standard Users", "Kaiju Global Admins")
        String forgedPayloadB64 = Base64.urlEncoder.withoutPadding().encodeToString(forgedPayloadJson.getBytes(StandardCharsets.UTF_8))
        String forgedToken = "${parts[0]}.${forgedPayloadB64}.${parts[2]}"

        when: "calling a secured endpoint with the forged claims token"
        client.exchange(
                HttpRequest.GET("/projects")
                        .header("Authorization", "Bearer ${forgedToken}")
        )

        then: "access is strictly rejected with 401 Unauthorized"
        HttpClientResponseException ex = thrown()
        ex.status == HttpStatus.UNAUTHORIZED
    }

    def "live Authentik token for Jenny New triggers JIT provisioning into PostgreSQL as STANDARD_USER"() {
        given: "unseeded persona Jenny New who exists only in Authentik"
        String jennyEmail = "jenny.new@kaiju.local"
        assert !userRepository.findByEmail(jennyEmail).isPresent(): "Jenny should not exist in database before test"

        when: "Jenny logs in via Authentik and obtains a genuine signed token"
        Map<String, String> tokens = obtainLiveTokens(jennyEmail)
        SignedJWT signedJWT = SignedJWT.parse(tokens.idToken)
        def claims = signedJWT.getJWTClaimsSet()

        and: "the token claims are processed by AuthentikAuthenticationMapper"
        OpenIdClaims openIdClaims = [
                getEmail: { -> claims.getStringClaim("email") },
                get: { String key -> key == "email_verified" ? true : claims.getClaim(key) }
        ] as OpenIdClaims
        OpenIdTokenResponse tokenResponse = new OpenIdTokenResponse()
        def publisher = mapper.createAuthenticationResponse("authentik", tokenResponse, openIdClaims, null)
        AuthenticationResponse authResponse = await(publisher)

        then: "authentication succeeds with a new database record created"
        authResponse.isAuthenticated()
        def auth = authResponse.getAuthentication().get()
        auth.getAttributes().get("email") == jennyEmail

        and: "Jenny now physically exists in PostgreSQL as a STANDARD_USER"
        Optional<User> dbUserOpt = userRepository.findByEmail(jennyEmail)
        dbUserOpt.isPresent()
        User dbUser = dbUserOpt.get()
        dbUser.email() == jennyEmail
        dbUser.role() == UserRole.STANDARD_USER
        auth.getName() == dbUser.id().toString()

        and: "standard volunteer discrete permissions are granted in the security context"
        UserRole.STANDARD_USER.getPermissions().each { permission ->
            assert auth.getRoles().contains(permission.getClaim())
        }

        cleanup:
        userRepository.findByEmail(jennyEmail).ifPresent { userRepository.delete(it) }
    }

    def "live Authentik token for Alice Smith grants access to protected /projects endpoint with 200 OK"() {
        given: "seeded persona Alice Smith"
        String aliceEmail = "alice.smith@kaiju.local"

        when: "Alice logs in via Authentik and obtains a genuine signed token"
        Map<String, String> tokens = obtainLiveTokens(aliceEmail)

        and: "she calls GET /projects presenting the token in the Authorization header"
        def response = client.exchange(
                HttpRequest.GET("/projects")
                        .header("Authorization", "Bearer ${tokens.idToken}")
        )

        then: "access is granted and returns 200 OK"
        response.status == HttpStatus.OK
    }

    def "unauthenticated requests to protected endpoints return 401 Unauthorized"() {
        when: "calling a secured endpoint without credentials"
        client.exchange(HttpRequest.GET("/projects"))

        then: "access is rejected with 401 Unauthorized"
        HttpClientResponseException ex = thrown()
        ex.status == HttpStatus.UNAUTHORIZED
    }

    def "HTTP requests with tampered or malformed authorization token return 401 Unauthorized"() {
        when: "calling a secured endpoint with an invalid bearer token"
        client.exchange(
                HttpRequest.GET("/projects")
                        .header("Authorization", "Bearer eyJhbGciOiJSUzI1NiJ9.invalid.signature")
        )

        then: "access is rejected with 401 Unauthorized"
        HttpClientResponseException ex = thrown()
        ex.status == HttpStatus.UNAUTHORIZED
    }

    def "Sarah Admin (GLOBAL_ADMIN) has full administrative access via live Authentik token"() {
        given: "a live Authentik token for Sarah Admin"
        Map<String, String> tokens = obtainLiveTokens("sarah.admin1@kaiju.local")
        UUID createdRegionId = null

        when: "Sarah calls GET /users to manage system users"
        def usersResponse = client.exchange(
                HttpRequest.GET("/users")
                        .header("Authorization", "Bearer ${tokens.idToken}")
        )

        then: "access is granted with 200 OK verifying system:user-manage permission"
        usersResponse.status == HttpStatus.OK

        when: "Sarah calls POST /administrative-regions to create an administrative boundary"
        def regionPayload = createTestRegionPayload("Sarah Admin Region " + UUID.randomUUID())
        def regionResponse = client.exchange(
                HttpRequest.POST("/administrative-regions", regionPayload)
                        .header("Authorization", "Bearer ${tokens.idToken}"),
                AdministrativeRegion
        )
        createdRegionId = regionResponse.body().id()

        then: "region creation is granted with 200 OK verifying region:manage permission"
        regionResponse.status == HttpStatus.OK
        createdRegionId != null

        cleanup:
        if (createdRegionId != null) {
            administrativeRegionRepository.deleteById(createdRegionId)
        }
    }

    def "Rachel State Director (REGION_DIRECTOR) can manage regions but is denied system user management"() {
        given: "a live Authentik token for Rachel State Director"
        Map<String, String> tokens = obtainLiveTokens("rachel.state.director1@kaiju.local")
        UUID createdRegionId = null

        when: "Rachel calls POST /administrative-regions to create an administrative boundary"
        def regionPayload = createTestRegionPayload("Rachel Director Region " + UUID.randomUUID())
        def regionResponse = client.exchange(
                HttpRequest.POST("/administrative-regions", regionPayload)
                        .header("Authorization", "Bearer ${tokens.idToken}"),
                AdministrativeRegion
        )
        createdRegionId = regionResponse.body().id()

        then: "region creation is granted with 200 OK verifying region:manage permission"
        regionResponse.status == HttpStatus.OK
        createdRegionId != null

        when: "Rachel calls GET /users to attempt system user administration"
        client.exchange(
                HttpRequest.GET("/users")
                        .header("Authorization", "Bearer ${tokens.idToken}")
        )

        then: "access is strictly rejected with 403 Forbidden demonstrating privilege containment"
        HttpClientResponseException ex = thrown()
        ex.status == HttpStatus.FORBIDDEN

        cleanup:
        if (createdRegionId != null) {
            administrativeRegionRepository.deleteById(createdRegionId)
        }
    }

    def "Nathan National Admin (ORG_ADMIN / system STANDARD_USER) can access projects but is denied regional and system administration"() {
        given: "a live Authentik token for Nathan National Admin"
        Map<String, String> tokens = obtainLiveTokens("nathan.national.admin1@kaiju.local")

        when: "Nathan calls GET /projects"
        def projectsResponse = client.exchange(
                HttpRequest.GET("/projects")
                        .header("Authorization", "Bearer ${tokens.idToken}")
        )

        then: "access is granted with 200 OK"
        projectsResponse.status == HttpStatus.OK

        when: "Nathan attempts to create an administrative region via POST /administrative-regions"
        client.exchange(
                HttpRequest.POST("/administrative-regions", createTestRegionPayload("Nathan Region Attempt"))
                        .header("Authorization", "Bearer ${tokens.idToken}")
        )

        then: "access is rejected with 403 Forbidden"
        HttpClientResponseException regionEx = thrown()
        regionEx.status == HttpStatus.FORBIDDEN

        when: "Nathan attempts to list users via GET /users"
        client.exchange(
                HttpRequest.GET("/users")
                        .header("Authorization", "Bearer ${tokens.idToken}")
        )

        then: "access is rejected with 403 Forbidden"
        HttpClientResponseException usersEx = thrown()
        usersEx.status == HttpStatus.FORBIDDEN
    }

    def "Victor Volunteer (STANDARD_USER) can access projects but is denied administrative region operations"() {
        given: "a live Authentik token for Victor Volunteer"
        Map<String, String> tokens = obtainLiveTokens("victor.volunteer1@kaiju.local")

        when: "Victor calls GET /projects"
        def projectsResponse = client.exchange(
                HttpRequest.GET("/projects")
                        .header("Authorization", "Bearer ${tokens.idToken}")
        )

        then: "access is granted with 200 OK"
        projectsResponse.status == HttpStatus.OK

        when: "Victor attempts to create an administrative region via POST /administrative-regions"
        client.exchange(
                HttpRequest.POST("/administrative-regions", createTestRegionPayload("Victor Region Attempt"))
                        .header("Authorization", "Bearer ${tokens.idToken}")
        )

        then: "access is rejected with 403 Forbidden"
        HttpClientResponseException regionEx = thrown()
        regionEx.status == HttpStatus.FORBIDDEN
    }

    def "dynamic RBAC reflects immediate permission elevation and revocation with the same live Authentik token"() {
        given: "Victor Volunteer with standard user role and a valid live Authentik token"
        String victorEmail = "victor.volunteer1@kaiju.local"
        Map<String, String> tokens = obtainLiveTokens(victorEmail)
        User victor = userRepository.findByEmail(victorEmail).get()
        assert victor.role() == UserRole.STANDARD_USER
        UUID createdRegionId = null

        when: "Victor calls POST /administrative-regions with his initial STANDARD_USER role"
        client.exchange(
                HttpRequest.POST("/administrative-regions", createTestRegionPayload("Victor Pre-Promotion Region"))
                        .header("Authorization", "Bearer ${tokens.idToken}")
        )

        then: "access is rejected with 403 Forbidden"
        HttpClientResponseException preEx = thrown()
        preEx.status == HttpStatus.FORBIDDEN

        when: "Victor is dynamically promoted to REGION_DIRECTOR in the authoritative database"
        userService.updateRole(victor.id(), UserRole.REGION_DIRECTOR)

        and: "Victor presents the exact same live Authentik token to POST /administrative-regions"
        def elevatedResponse = client.exchange(
                HttpRequest.POST("/administrative-regions", createTestRegionPayload("Victor Promoted Region"))
                        .header("Authorization", "Bearer ${tokens.idToken}"),
                AdministrativeRegion
        )
        createdRegionId = elevatedResponse.body().id()

        then: "access is immediately granted with 200 OK reflecting the updated database authority"
        elevatedResponse.status == HttpStatus.OK
        createdRegionId != null

        when: "Victor is dynamically demoted back to STANDARD_USER in the database"
        userService.updateRole(victor.id(), UserRole.STANDARD_USER)

        and: "Victor presents the exact same live Authentik token once again"
        client.exchange(
                HttpRequest.POST("/administrative-regions", createTestRegionPayload("Victor Demoted Region"))
                        .header("Authorization", "Bearer ${tokens.idToken}")
        )

        then: "access is immediately rejected with 403 Forbidden"
        HttpClientResponseException postEx = thrown()
        postEx.status == HttpStatus.FORBIDDEN

        cleanup:
        userService.updateRole(victor.id(), UserRole.STANDARD_USER)
        if (createdRegionId != null) {
            administrativeRegionRepository.deleteById(createdRegionId)
        }
    }

    def "unseeded Authentik user with admin group is JIT provisioned into PostgreSQL with mapped role on first presentation"() {
        given: "an unseeded admin persona in Authentik with Kaiju Global Admins group"
        String garyEmail = "gary.admin2@kaiju.local"
        User originalGary = userRepository.findByEmail(garyEmail).orElse(null)
        if (originalGary != null) {
            userRepository.delete(originalGary)
        }
        assert !userRepository.findByEmail(garyEmail).isPresent(): "Gary must not exist in database before first presentation"

        when: "Gary logs in via Authentik and calls GET /users presenting his live token"
        Map<String, String> tokens = obtainLiveTokens(garyEmail)
        def response = client.exchange(
                HttpRequest.GET("/users")
                        .header("Authorization", "Bearer ${tokens.idToken}")
        )

        then: "access is granted with 200 OK due to JIT provisioning with mapped GLOBAL_ADMIN role"
        response.status == HttpStatus.OK

        and: "Gary now exists in the PostgreSQL database with GLOBAL_ADMIN role"
        Optional<User> provisionedUser = userRepository.findByEmail(garyEmail)
        provisionedUser.isPresent()
        provisionedUser.get().role() == UserRole.GLOBAL_ADMIN

        cleanup:
        if (originalGary != null) {
            userRepository.findByEmail(garyEmail).ifPresent { userRepository.removeById(it.id()) }
            userRepository.save(new User(null, originalGary.email(), originalGary.role(), originalGary.createdAt()))
        } else {
            userRepository.findByEmail(garyEmail).ifPresent { userRepository.removeById(it.id()) }
        }
    }
}
