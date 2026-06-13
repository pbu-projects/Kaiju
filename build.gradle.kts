plugins {
    id("java")
    id("io.micronaut.application") version "4.4.2" apply false
    id("io.micronaut.library") version "4.4.2" apply false
    id("com.gradleup.shadow") version "8.3.3" apply false
    id("io.micronaut.aot") version "4.4.2" apply false
    id("gg.jte.gradle") version "3.1.12" apply false
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
    java {
        sourceCompatibility = JavaVersion.toVersion("21")
        targetCompatibility = JavaVersion.toVersion("21")
    }
}
