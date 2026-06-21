plugins {
    id("java")
    id("io.micronaut.application") version "4.4.2" apply false
    id("io.micronaut.library") version "4.4.2" apply false
    id("com.gradleup.shadow") version "8.3.3" apply false
    id("io.micronaut.aot") version "4.4.2" apply false
    id("gg.jte.gradle") version "3.1.12" apply false
    id("org.sonarqube") version "latest.release"
    id("jacoco")
}

allprojects {
    group = "lol.pbu.kaiju"
    version = project.properties["kaijuVersion"]!!

    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

sonarqube {
    properties {
        property("sonar.tests", "src/test/groovy")
    }
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
