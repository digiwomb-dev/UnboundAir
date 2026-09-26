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

    // Mutation testing (own task, never part of `build`/`check`). Verified on
    // JUnit Platform 6 with pitest 1.25.5 - see docs/entscheidungen.md (Spike B).
    id("info.solidsoft.pitest") version "1.19.0"
}

group = "dev.digiwomb.unboundair"
version = "0.0.1"

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

    // Test tooling, pinned to the newest stable release and test-scope only so
    // the runtime classpath stays untouched (DC-03, "Abhängigkeiten minimal").
    // See docs/plan.md (test-dependency table) and docs/entscheidungen.md for
    // the selection rationale.
    //
    // Property-based tests. jqwik (>= 1.10) forbids use by AI coding agents,
    // which this project relies on; kotest-property has no such clause and no
    // engine of its own (it is called from plain @Test methods).
    testImplementation("io.kotest:kotest-property:6.2.5")

    // Contract tests against the paperless-ngx HTTP API. 4.x is still beta,
    // so 3.13.2 is the newest stable line.
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")

    // Architecture guard (Wächter). The junit6 artifact carries JUnit Platform 6
    // support, introduced in ArchUnit 1.5.0.
    testImplementation("com.tngtech.archunit:archunit-junit6:1.5.0")
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
    useJUnitPlatform {
        // Explicit engine whitelist: Jupiter for the regular tests, ArchUnit
        // for the guard (Wächter) tests. kotest-property brings no engine.
        includeEngines("junit-jupiter", "archunit")
    }
}

// Predictable artifact name, so scripts and the container image do not
// have to track the version number.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("unboundair.jar")
}

// Mutation testing (Spike B, docs/entscheidungen.md). PIT is not wired into
// `build` or `check`; run it explicitly with `./gradlew pitest`. Versions are
// pinned: gradle-pitest-plugin 1.19.0 defaults to pitest 1.22.1, which predates
// the JUnit Platform 6 fix, so pitest 1.25.5 is set explicitly together with the
// matching pitest-junit5-plugin. The core packages are the mutation target; the
// scanner tests are timing-sensitive, so the per-test timeout is raised.
pitest {
    pitestVersion.set("1.25.5")
    junit5PluginVersion.set("1.2.2")
    targetClasses.set(
        setOf(
            "dev.digiwomb.unboundair.scanner.*",
            "dev.digiwomb.unboundair.image.*",
            "dev.digiwomb.unboundair.processing.*",
        ),
    )
    targetTests.set(
        setOf(
            "dev.digiwomb.unboundair.scanner.*",
            "dev.digiwomb.unboundair.image.*",
            "dev.digiwomb.unboundair.processing.*",
        ),
    )
    outputFormats.set(setOf("HTML"))
    threads.set(1)
    timestampedReports.set(false)
    timeoutConstInMillis.set(60000)

    // Floor, not a target. 73 % is exactly what the first full run over all
    // three core packages measured (280/386 killed, 25.09.2026); pinning it
    // here makes a later drop in assertion quality fail the task instead of
    // passing unnoticed. No previous value was lowered - there was none.
    // Raise this number when the score improves; never lower it silently.
    // Per-package numbers and the weak spots are in docs/entscheidungen.md.
    mutationThreshold.set(73)
}
