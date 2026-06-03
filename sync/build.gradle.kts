plugins {
    id("io.micronaut.library") version "4.4.2"
}

version = "0.1"
group = "lol.pbu.kaiju"

dependencies {
    implementation(project(":core"))
    
    annotationProcessor("io.micronaut:micronaut-http-validation")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    
    implementation("io.micronaut:micronaut-retry")
    implementation("io.micronaut.reactor:micronaut-reactor-http-client")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    
    runtimeOnly("ch.qos.logback:logback-classic")
}

micronaut {
    runtime("netty")
    testRuntime("spock2")
    processing {
        incremental(true)
        annotations("lol.pbu.kaiju.sync.*")
    }
}
