plugins {
    id("io.micronaut.application") version "4.4.2"
    id("gg.jte.gradle") version "3.1.12"
    id("com.gradleup.shadow") version "8.3.3"
    id("io.micronaut.aot") version "4.4.2"
}

version = "0.1"
group = "lol.pbu.kaiju"

dependencies {
    implementation(project(":core"))
    
    annotationProcessor("io.micronaut:micronaut-http-validation")
    annotationProcessor("io.micronaut.security:micronaut-security-annotations")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    
    implementation("io.micronaut:micronaut-management")
    implementation("io.micronaut.micrometer:micronaut-micrometer-core")
    implementation("io.micronaut.micrometer:micronaut-micrometer-observation-http")
    implementation("io.micronaut.security:micronaut-security")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    implementation("io.micronaut.views:micronaut-views-jte")
    
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")

    testImplementation("io.micronaut:micronaut-http-client")
    testImplementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:spock")
    testImplementation("org.testcontainers:testcontainers")
}

application {
    mainClass.set("lol.pbu.kaiju.server.Application")
}

micronaut {
    runtime("netty")
    testRuntime("spock2")
    processing {
        incremental(true)
        annotations("lol.pbu.kaiju.server.*")
    }
    aot {
        optimizeServiceLoading.set(false)
        convertYamlToJava.set(false)
        precomputeOperations.set(true)
        cacheEnvironment.set(true)
        optimizeClassLoading.set(true)
        deduceEnvironment.set(true)
        optimizeNetty.set(true)
        replaceLogbackXml.set(true)
    }
}

jte {
    sourceDirectory.set(file("src/main/resources/views").toPath())
    generate()
}
