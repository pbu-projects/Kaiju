plugins {
    id("groovy") 
    id("io.micronaut.application") version "5.0.2"
    id("com.gradleup.shadow") version "9.4.1"
    id("io.micronaut.aot") version "5.0.2"
    id("io.micronaut.test-resources") version "5.0.2"
    id("org.sonarqube") version "5.1.0.4882"
}

version = project.properties["kaijuVersion"]!!
group = "lol.pbu"



repositories {
    mavenCentral()
}

dependencies {
    annotationProcessor("io.micronaut.data:micronaut-data-processor")
    annotationProcessor("io.micronaut:micronaut-http-validation")
    annotationProcessor("io.micronaut.security:micronaut-security-processor")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    annotationProcessor("io.micronaut.validation:micronaut-validation-processor")
    implementation("io.micronaut.flyway:micronaut-flyway")
    implementation("io.micronaut.data:micronaut-data-jdbc")
    implementation("io.micronaut.security:micronaut-security")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    implementation("io.micronaut.sql:micronaut-jdbc-hikari")
    implementation("io.micronaut.validation:micronaut-validation")
    implementation("jakarta.validation:jakarta.validation-api")
    implementation("org.locationtech.jts:jts-core:${project.properties["jtsVersion"]}")
    compileOnly("io.micronaut:micronaut-http-client")
    implementation("org.postgresql:postgresql")
    implementation("io.micronaut:micronaut-retry")
    implementation("io.micronaut.reactor:micronaut-reactor-http-client")
    implementation("io.micronaut:micronaut-management")
    implementation("io.micronaut.micrometer:micronaut-micrometer-core")
    implementation("io.micronaut.micrometer:micronaut-micrometer-observation-http")
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.yaml:snakeyaml")
    testImplementation("org.apache.commons:commons-compress:${project.properties["commonsCompressVersion"]}")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-spock")
    testImplementation("org.apache.groovy:groovy-sql:${project.properties["groovySqlVersion"]}")
    testImplementation("net.datafaker:datafaker:${project.properties["datafakerVersion"]}")
}



application {
    mainClass = "lol.pbu.Application"
}

java {
    sourceCompatibility = JavaVersion.toVersion("25")
    targetCompatibility = JavaVersion.toVersion("25")
}




graalvmNative.toolchainDetection = false
graalvmNative {
    binaries {
        all {
            buildArgs.add("-H:+SharedArenaSupport")
        }
    }
}




micronaut {
    runtime("netty")
    testRuntime("spock2")
    processing {
        incremental(true)
        annotations("lol.pbu.*")
    }
    aot {
        // Please review carefully the optimizations enabled below
        // Check https://micronaut-projects.github.io/micronaut-aot/latest/guide/ for more details
        optimizeServiceLoading = false
        convertYamlToJava = false
        precomputeOperations = true
        cacheEnvironment = true
        optimizeClassLoading = true
        deduceEnvironment = true
        optimizeNetty = true
        replaceLogbackXml = true
    }
}
sonar {
    properties {
        property("sonar.projectKey", "pbu-projects_Kaiju")
        property("sonar.organization", "peanutbutter-unicorn")
        property("sonar.host.url", "https://sonarcloud.io")
        (System.getenv("SONAR_TOKEN") ?: System.getenv("sonar_token"))?.let { token ->
            if (token.isNotBlank()) {
                property("sonar.token", token)
            }
        }
    }
}

tasks.named<io.micronaut.gradle.docker.MicronautDockerfile>("dockerfile") {
    baseImage = "eclipse-temurin:25-jre"
}
// https://docs.gradle.org/current/userguide/upgrading_major_version_9.html#test_task_fails_when_no_tests_are_discovered
tasks.withType<AbstractTestTask>().configureEach {
    failOnNoDiscoveredTests = false
}

tasks.register("lighthouse") {
    group = "verification"
    description = "Lighthouse Markdown audit: validates syntax, block rendering, and link integrity."
    doLast {
        val rootDir = projectDir
        val ignoreDirs = setOf(".git", ".gradle", "build", "node_modules", ".idea", ".vscode", ".agent", ".gemini")
        val mdFiles = rootDir.walkTopDown()
            .onEnter { it.name !in ignoreDirs }
            .filter { it.isFile && it.extension == "md" }
            .toList()
            .sortedBy { it.relativeTo(rootDir).path }

        println("🗼 Running Lighthouse Markdown Audit across ${mdFiles.size} files...")

        val linkPattern = Regex("""(!?)\[([^\]]*)\]\(([^)]+)\)""")
        val headingPattern = Regex("""^(#{1,6})\s+(.+)$""")
        val lineRefPattern = Regex("""^L(\d+)(?:-L?(\d+))?$""")

        fun slugify(title: String): String {
            val cleaned = title.replace(Regex("""\[([^\]]+)\]\([^)]+\)"""), "$1")
                .replace(Regex("""[*_`]"""), "")
                .lowercase()
                .replace(Regex("""[^a-z0-9\s-]"""), "")
            return cleaned.trim().replace(Regex("""\s+"""), "-").replace(Regex("""-+"""), "-")
        }

        var totalLinks = 0
        var totalAnchors = 0
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        for (file in mdFiles) {
            val relPath = file.relativeTo(rootDir).path.replace('\\', '/')
            val rawContent = file.readText(Charsets.UTF_8)
            val lines = rawContent.lines()

            // 1. Check EOF newline
            if (!rawContent.endsWith("\n")) {
                errors.add("❌ [ERROR] $relPath:${lines.size} [Formatting] File missing trailing newline character")
            } else if (rawContent.endsWith("\n\n\n")) {
                warnings.add("⚠️ [WARNING] $relPath:${lines.size} [Formatting] Multiple consecutive trailing newlines at end of file")
            }

            // 2. Check first line heading
            val firstNonEmpty = lines.firstOrNull { it.isNotBlank() }
            if (firstNonEmpty != null && !firstNonEmpty.startsWith("#")) {
                warnings.add("⚠️ [WARNING] $relPath:1 [Syntax] File does not start with a top-level heading (# Title)")
            }

            // 3. Code fence balancing and blank lines
            var inCodeBlock = false
            var codeStartLine = 0
            for ((idx, line) in lines.withIndex()) {
                val lineNo = idx + 1
                val stripped = line.trim()
                if (stripped.startsWith("```")) {
                    if (!inCodeBlock) {
                        inCodeBlock = true
                        codeStartLine = lineNo
                        if (idx > 0 && lines[idx - 1].isNotBlank() && !lines[idx - 1].trim().startsWith("#")) {
                            warnings.add("⚠️ [WARNING] $relPath:$lineNo [Syntax] Code fence missing blank line above")
                        }
                    } else {
                        inCodeBlock = false
                        if (idx + 1 < lines.size && lines[idx + 1].isNotBlank()) {
                            warnings.add("⚠️ [WARNING] $relPath:$lineNo [Syntax] Closing code fence missing blank line below")
                        }
                    }
                } else if (!inCodeBlock) {
                    val headingMatch = headingPattern.matchEntire(stripped)
                    if (headingMatch != null) {
                        val title = headingMatch.groupValues[2]
                        if (title.endsWith(":")) {
                            warnings.add("⚠️ [WARNING] $relPath:$lineNo [Syntax] Heading has trailing colon: '$stripped'")
                        }
                        if (idx + 1 < lines.size && lines[idx + 1].isNotBlank()) {
                            errors.add("❌ [ERROR] $relPath:$lineNo [Syntax] Heading '$title' not followed by a blank line before next element")
                        }
                    }
                    if (line.contains("file:///")) {
                        errors.add("❌ [ERROR] $relPath:$lineNo [Link] Contains hardcoded 'file:///' URL; use relative repo path")
                    }
                }
            }
            if (inCodeBlock) {
                errors.add("❌ [ERROR] $relPath:$codeStartLine [Syntax] Unclosed code fence block")
            }

            // 4. Link audit
            val parentDir = file.parentFile
            for ((idx, line) in lines.withIndex()) {
                val lineNo = idx + 1
                for (match in linkPattern.findAll(line)) {
                    totalLinks++
                    val target = match.groupValues[3].trim()
                    if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:")) {
                        continue
                    }
                    val parts = target.split('#', limit = 2)
                    val relFile = parts[0].trim()
                    val fragment = if (parts.size > 1) parts[1].trim() else null

                    val targetFile = if (relFile.isEmpty()) file else parentDir.resolve(relFile).normalize()
                    if (!targetFile.exists()) {
                        errors.add("❌ [ERROR] $relPath:$lineNo [Link] Broken target path '$relFile' (does not exist on disk)")
                        continue
                    }

                    if (fragment != null) {
                        totalAnchors++
                        val lineMatch = lineRefPattern.matchEntire(fragment)
                        if (lineMatch != null) {
                            val startL = lineMatch.groupValues[1].toInt()
                            val endL = if (lineMatch.groupValues[2].isNotEmpty()) lineMatch.groupValues[2].toInt() else startL
                            val targetLines = targetFile.readLines(Charsets.UTF_8).size
                            if (startL > targetLines || endL > targetLines) {
                                errors.add("❌ [ERROR] $relPath:$lineNo [Ref] Line reference '$fragment' out of bounds (target file has $targetLines lines)")
                            }
                        } else if (targetFile.extension == "md") {
                            val targetHeadings = targetFile.readLines(Charsets.UTF_8)
                                .filter { it.trim().startsWith("#") }
                                .map { slugify(it.trim().replace(Regex("""^#+\s*"""), "")) }
                            val fragSlug = slugify(fragment)
                            if (fragSlug !in targetHeadings && fragment.lowercase() !in targetHeadings) {
                                warnings.add("⚠️ [WARNING] $relPath:$lineNo [Ref] Anchor '#$fragment' not found in target file headings")
                            }
                        }
                    }
                }
            }
        }

        val syntaxErrors = errors.count { it.contains("[Syntax]") || it.contains("[Formatting]") }
        val linkErrors = errors.count { it.contains("[Link]") }
        val refErrors = errors.count { it.contains("[Ref]") }

        val syntaxScore = maxOf(0, 100 - (syntaxErrors * 20))
        val linkScore = maxOf(0, 100 - (linkErrors * 25))
        val refScore = maxOf(0, 100 - (refErrors * 20))
        val overallScore = (syntaxScore + linkScore + refScore) / 3

        println("\n" + "=".repeat(64))
        println("  🗼 LIGHTHOUSE MARKDOWN & LINK INTEGRITY REPORT")
        println("=".repeat(64))
        println("  Files Scanned:        %3d".format(mdFiles.size))
        println("  Internal Links:       %3d".format(totalLinks))
        println("  Anchors & Line Refs:  %3d".format(totalAnchors))
        println("-".repeat(64))
        println("  Syntax & Rendering:   %3d%% %s".format(syntaxScore, if (syntaxScore == 100) "🟢 PASS" else "🔴 FAIL"))
        println("  Link Integrity:       %3d%% %s".format(linkScore, if (linkScore == 100) "🟢 PASS" else "🔴 FAIL"))
        println("  Anchor & Line Refs:   %3d%% %s".format(refScore, if (refScore == 100) "🟢 PASS" else "🔴 FAIL"))
        println("-".repeat(64))
        println("  Overall Health:       %3d%% %s".format(overallScore, if (errors.isEmpty()) "🟢 ALL KOSHER" else "🔴 ISSUES FOUND"))
        println("=".repeat(64) + "\n")

        if (errors.isNotEmpty() || warnings.isNotEmpty()) {
            println("Findings:")
            (errors + warnings).forEach { println("  $it") }
            println("\n")
        } else {
            println("✨ All markdown documents are syntactically accurate and all links are kosher!\n")
        }

        if (errors.isNotEmpty()) {
            throw GradleException("Lighthouse Markdown audit failed with ${errors.size} error(s).")
        }
    }
}

tasks.named("check") {
    dependsOn("lighthouse")
}
