package lol.pbu.kaiju

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import spock.lang.Shared
import spock.lang.Specification

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

class NativeImageSmokeSpec extends Specification {

    @Shared
    PostgreSQLContainer<?> postgres

    @Shared
    Process nativeProcess

    @Shared
    Path logFile

    @Shared
    int serverPort

    @Shared
    HttpClient httpClient

    def setupSpec() {
        def binaryPath = System.getProperty("kaiju.native.binary")
        assert binaryPath: "System property 'kaiju.native.binary' must be set"
        def binaryFile = Path.of(binaryPath).toFile()
        assert binaryFile.exists() && binaryFile.canExecute(): "Native binary not found or not executable: ${binaryPath}"

        postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:15-3.5").asCompatibleSubstituteFor("postgres")
        )
        postgres.start()

        serverPort = findFreePort()
        logFile = Files.createTempFile("kaiju-native-", ".log")

        def processBuilder = new ProcessBuilder(binaryPath)
        processBuilder.redirectErrorStream(true)
        processBuilder.redirectOutput(logFile.toFile())
        processBuilder.environment().putAll([
            MICRONAUT_SERVER_PORT       : serverPort.toString(),
            DATASOURCES_DEFAULT_URL     : postgres.jdbcUrl,
            DATASOURCES_DEFAULT_USERNAME: postgres.username,
            DATASOURCES_DEFAULT_PASSWORD: postgres.password
        ])

        nativeProcess = processBuilder.start()
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build()

        waitForReady()
    }

    def cleanupSpec() {
        if (nativeProcess != null) {
            nativeProcess.destroy()
            if (!nativeProcess.waitFor(5, TimeUnit.SECONDS)) {
                nativeProcess.destroyForcibly()
            }
        }
        postgres?.stop()
        if (logFile != null) {
            Files.deleteIfExists(logFile)
        }
    }

    def "GET /projects returns 200 and non-empty JSON"() {
        when:
        def response = get("/projects")

        then:
        verifyAll(response) {
            statusCode() == 200
            headers().firstValue("content-type").orElse("").contains("application/json")
            !body().isBlank()
            body().trim().startsWith("[") || body().trim().startsWith("{")
        }
    }

    def "GET /projects/search-by-location returns 200 and JSON content"() {
        when:
        def response = get("/projects/search-by-location?latitude=39.7392&longitude=-104.9903&radiusMeters=25000")

        then:
        verifyAll(response) {
            statusCode() == 200
            headers().firstValue("content-type").orElse("").contains("application/json")
            !body().isBlank()
            body().trim().startsWith("[") || body().trim().startsWith("{")
        }
    }

    def "GET /administrative-regions without auth returns 401"() {
        when:
        def response = get("/administrative-regions")

        then:
        response.statusCode() == 401
    }

    private HttpResponse<String> get(String path) {
        def request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:${serverPort}${path}"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private void waitForReady() {
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            if (!nativeProcess.isAlive()) {
                def output = Files.readString(logFile)
                throw new IllegalStateException(
                    "Native binary exited with code ${nativeProcess.exitValue()} before becoming ready.\nOutput:\n${output}"
                )
            }
            try {
                def response = get("/projects")
                if (response.statusCode() == 200) {
                    return
                }
            } catch (Exception ignored) {
                // Server is still starting up
            }
            Thread.sleep(200)
        }
        def output = Files.readString(logFile)
        throw new IllegalStateException("Timed out waiting for native binary on port ${serverPort}.\nOutput:\n${output}")
    }

    private static int findFreePort() {
        new ServerSocket(0).withCloseable { socket ->
            return socket.localPort
        }
    }
}
