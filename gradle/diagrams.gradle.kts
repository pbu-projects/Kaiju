import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

fun sanitizeSvgForPdf(rawSvg: String): String {
    var svg = rawSvg

    // 1. Give message arrow lines an explicit stroke color so prawn-svg renders them
    svg = svg.replace(Regex("""(<line[^>]*class="messageLine[01]"[^>]*?)stroke="none"([^>]*>)""")) { match ->
        "${match.groupValues[1]}stroke=\"#333333\"${match.groupValues[2]}"
    }

    // 2. Make vertical lifelines clear and visible with Mermaid theme purple stroke
    svg = svg.replace("stroke-width=\"0.5px\" stroke=\"#999\"", "stroke-width=\"1.5px\" stroke=\"#9370DB\"")

    // 3. Give participant boxes Mermaid theme lavender background and purple border
    svg = svg.replace("fill=\"#eaeaea\" stroke=\"#666\"", "fill=\"#ECECFF\" stroke=\"#9370DB\" stroke-width=\"1.5px\"")

    // 4. Fix marker orientation so left-pointing return arrows rotate properly in prawn-svg
    svg = svg.replace("orient=\"auto-start-reverse\"", "orient=\"auto\"")

    // 5. Strip root container prefix from CSS selectors so class selectors match in PDF renderer
    svg = svg.replace(Regex("<style>(.*?)</style>", RegexOption.DOT_MATCHES_ALL)) { match ->
        val style = match.groupValues[1].replace(Regex("#container\\s+"), "")
        "<style>$style</style>"
    }

    return svg
}

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
                .header("User-Agent", "Mozilla/5.0 (Kaiju Docs Generator)")
                .POST(HttpRequest.BodyPublishers.ofFile(sourceFile.toPath()))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val cleanedSvg = sanitizeSvgForPdf(response.body())
                val targetFile = outputDir.resolve("${sourceFile.nameWithoutExtension}.svg")
                targetFile.writeText(cleanedSvg)
                logger.lifecycle("Generated SVG: ${targetFile.relativeTo(projectDir)}")
            } else {
                throw GradleException(
                    "Failed to generate SVG for ${sourceFile.name}: HTTP ${response.statusCode()}\n${response.body()}"
                )
            }
        }
    }
}
