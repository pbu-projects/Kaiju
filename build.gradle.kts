plugins {
    id("java")
    id("jacoco")
    id("io.micronaut.application") version "4.4.2" apply false
    id("io.micronaut.library") version "4.4.2" apply false
    id("com.gradleup.shadow") version "8.3.3" apply false
    id("io.micronaut.aot") version "4.4.2" apply false
    id("gg.jte.gradle") version "3.1.12" apply false
    id("org.sonarqube") version "7.3.1.8318"
}

allprojects {
    group = "lol.pbu.kaiju"
    version = "0.1"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "jacoco")

    java {
        sourceCompatibility = JavaVersion.toVersion("21")
        targetCompatibility = JavaVersion.toVersion("21")
    }

    tasks.withType<JacocoReport> {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    tasks.withType<Test> {
        finalizedBy(tasks.withType<JacocoReport>())
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


