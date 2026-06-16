plugins {
    id("io.micronaut.library")
    id("groovy")
}

version = "0.0.1"
group = "lol.pbu.kaiju"

dependencies {
    annotationProcessor("io.micronaut.data:micronaut-data-processor")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    annotationProcessor("io.micronaut.validation:micronaut-validation-processor")
    annotationProcessor("io.micronaut.sourcegen:micronaut-sourcegen-generator-java")

    implementation("io.micronaut.validation:micronaut-validation")
    implementation("io.micronaut.sourcegen:micronaut-sourcegen-annotations")
    implementation("io.micronaut.data:micronaut-data-jdbc")
    implementation("io.micronaut.sql:micronaut-jdbc-hikari")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    implementation("org.locationtech.jts:jts-core:${project.properties["jtsVersion"]}")
    implementation("org.postgresql:postgresql")

    testImplementation("org.apache.groovy:groovy-sql:${project.properties["groovySqlVersion"]}")
    testImplementation("net.datafaker:datafaker:${project.properties["datafakerVersion"]}")

    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")

}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("started", "passed", "skipped", "failed")
    }
}

//tasks.withType<JavaExec>().configureEach {
//    jvmArgs("-Xshare:off")
//}
//
//tasks.withType<Test>().configureEach {
//    jvmArgs("-Xshare:off")
//}

micronaut {
    runtime("netty")
    testRuntime("spock2")
    processing {
        incremental(true)
        annotations("lol.pbu.kaiju.core.*")
    }
}


