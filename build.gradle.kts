// All versions are pinned deliberately. The project follows the newest
// stable release rather than the newest LTS; see docs/plan.md, "Feste
// Entscheidungen", for the version table and the reasoning.

plugins {
    // Kotlin is pulled in ahead of the Spring Boot plugin so the version
    // below wins over the one Spring Boot manages. Spring Boot 4.1.1
    // manages Kotlin 2.3.21, whose compiler cannot target Java 26.
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.spring") version "2.4.20"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"

    // Runs ktlint. Not applied through the ktlint Gradle plugin on purpose:
    // Spring Boot's dependency management imports the Kotlin BOM and applies
    // it to every configuration, which replaces ktlint's own compiler with
    // the project's 2.4.20 and makes it crash. Spotless resolves its tools
    // through a detached configuration, which that mechanism does not touch.
    // See docs/plan.md, "Entschieden", and OF-11.
    id("com.diffplug.spotless") version "8.10.2"
}

group = "dev.digiwomb.unboundair"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(26)
}

repositories {
    mavenCentral()
}

dependencies {
    // Deliberately minimal. No web starter: the service has no HTTP
    // endpoint in v1, and a servlet container would only get in the way
    // of a command line application.
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // PDF assembly. Version pinned here rather than inherited, since
    // Spring Boot does not manage PDFBox.
    implementation("org.apache.pdfbox:pdfbox:3.0.8")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(kotlin("test"))
}

spotless {
    // The ktlint version is pinned explicitly rather than left to Spotless,
    // so an update of the plugin cannot silently change the rule set.
    kotlin {
        target("src/**/*.kt")
        ktlint("1.8.0")
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.8.0")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Predictable artifact name, so scripts and the container image do not
// have to track the version number.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("unboundair.jar")
}
