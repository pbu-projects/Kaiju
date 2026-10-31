import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

tasks.register("generateDiagrams") {
    group = "documentation"
    description = "Generates SVG images from diagram source files using the Kroki API."

    val inputDir = layout.projectDirectory.dir("src/docs/asciidoc/diagrams").asFile
    val outputDir = layout.projectDirectory.dir("src/docs/asciidoc/images").asFile

    inputs.dir(inputDir)
    outputs.dir(outputDir)

    doLast {
        if (!inputDir.exists()) {
            logger.info("Diagram source directory does not exist: ${inputDir.path}")
            return@doLast
        }

        outputDir.mkdirs()
        val client = HttpClient.newHttpClient()

        inputDir.walkTopDown().filter { it.isFile }.forEach { sourceFile ->
            val diagramType = when (sourceFile.extension.lowercase()) {
                "mmd", "mermaid" -> "mermaid"
                "puml", "plantuml" -> "plantuml"
                "d2" -> "d2"
                else -> null
            } ?: return@forEach

            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://kroki.io/$diagramType/svg"))
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofFile(sourceFile.toPath()))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val targetFile = outputDir.resolve("${sourceFile.nameWithoutExtension}.svg")
                targetFile.writeText(response.body())
                logger.lifecycle("Generated SVG: ${targetFile.relativeTo(projectDir)}")
            } else {
                throw GradleException(
                    "Failed to generate SVG for ${sourceFile.name}: HTTP ${response.statusCode()}\n${response.body()}"
                )
            }
        }
    }
}
