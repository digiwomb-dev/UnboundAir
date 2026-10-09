// All versions are pinned deliberately. The project follows the newest
// stable release rather than the newest LTS; see docs/internal/plan.md, "Feste
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
    // See docs/internal/plan.md, "Entschieden", and OF-11.
    id("com.diffplug.spotless") version "8.10.2"

    // Mutation testing (own task, never part of `build`/`check`). Verified on
    // JUnit Platform 6 with pitest 1.25.5 - see docs/internal/entscheidungen.md (Spike B).
    id("info.solidsoft.pitest") version "1.19.0"
}

group = "dev.digiwomb.unboundair"
version = "0.0.5"

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

    // Outbound upload to paperless-ngx (AU-05). Client only: this adds no
    // endpoint of its own, so the "no web starter" note above stays true.
    implementation("org.springframework.boot:spring-boot-starter-restclient")

    // Jackson for reading and writing the outbox metadata.json (AU-04).
    // Jackson 3 changed its coordinates: the Kotlin module is
    // tools.jackson.module, not com.fasterxml.jackson.module - the old group
    // resolves and compiles, but fails at runtime for a Kotlin data class
    // without a default constructor.
    implementation("tools.jackson.module:jackson-module-kotlin")

    // PDF assembly. Version pinned here rather than inherited, since
    // Spring Boot does not manage OpenPDF.
    //
    // brotli4j is excluded: OpenPDF's POM lists it without <optional>true</optional>
    // (unlike its other optional deps), so it would land on the runtime classpath
    // transitively. Its Brotli content-stream compression is opt-in and default-off
    // (Document.useBrotliCompression = false, spike #141), it carries native
    // libraries, and the GraalVM option the plan keeps open argues against it.
    // Spike #141 built a PDF fine without it.
    implementation("com.github.librepdf:openpdf:3.0.5") {
        exclude(group = "com.aayushatharva.brotli4j")
    }

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(kotlin("test"))

    // PDFBox is the independent verifier of what OpenPDF produced - test scope
    // only, the runtime classpath stays untouched. A verifier that is the same
    // code as the producer proves nothing, and COSStream.createRawInputStream()
    // is how the tests prove a JPEG went into the PDF without re-encoding.
    // Version pinned here rather than inherited, since Spring Boot does not
    // manage PDFBox.
    testImplementation("org.apache.pdfbox:pdfbox:3.0.8")

    // Test tooling, pinned to the newest stable release and test-scope only so
    // the runtime classpath stays untouched (DC-03).
    // See docs/internal/plan.md (test-dependency table) and docs/internal/entscheidungen.md for
    // the selection rationale.
    //
    // Property-based tests. jqwik (>= 1.10) forbids use by AI coding agents,
    // which this project relies on; kotest-property has no such clause and no
    // engine of its own (it is called from plain @Test methods).
    testImplementation("io.kotest:kotest-property:6.2.5")

    // Contract tests against the paperless-ngx HTTP API. 4.x is still beta,
    // so 3.13.2 is the newest stable line.
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")

    // Contract tests: JSON schema validation of the paperless payload.
    // Version pinned: Spring Boot does not manage com.networknt.
    testImplementation("com.networknt:json-schema-validator:3.0.8")

    // Architecture guard (Wächter). The junit6 artifact carries JUnit Platform 6
    // support, introduced in ArchUnit 1.5.0.
    testImplementation("com.tngtech.archunit:archunit-junit6:1.5.1")
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

    // RepositoryHygieneTest checks the documentation itself (DO-08), so the
    // documentation is an input of the test task. Without this, editing a
    // Markdown file leaves `test` UP-TO-DATE and the guard reports the
    // previous run - it would pass on a broken link until something in
    // src/ happens to change.
    inputs
        .files(
            layout.projectDirectory.file("README.md"),
            layout.projectDirectory.file("CONTRIBUTING.md"),
            layout.projectDirectory.file("SECURITY.md"),
            layout.projectDirectory.file("AGENTS.md"),
        ).withPropertyName("rootDocumentation")
        .optional()
        .withPathSensitivity(PathSensitivity.RELATIVE)

    inputs
        .dir(layout.projectDirectory.dir("docs"))
        .withPropertyName("documentationDirectory")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Predictable artifact name, so scripts and the container image do not
// have to track the version number.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("unboundair.jar")
}

// Standalone FakeScanner for the container check (CT-01, work order 8).
// Starts the existing test-scope FakeScanner as a TCP server on a fixed
// port, so `status` (BE-01) gets a real answer where only unboundair.jar
// exists and no test classes are on the classpath. The class stays in the
// test source set and never ships in the runtime jar; this task only puts
// the test runtime classpath on its own classpath.
//
// Properties, both optional:
// - `-PfakeScannerPort=<n>`: the public port to serve on. Default 2323.
//   Work order 9's workflow uses the default.
// - `-PfakeScannerPage=<path>`: a file loaded into the tray as one sheet,
//   so scans deliver a real page. Without it the fake answers `scanready`
//   with its defaults, which is what the `status` check needs.
//
// The task keeps running while it serves; stop it with Ctrl+C. Everything
// served is logged to stdout, so a failing container check can be read.
tasks.register<JavaExec>("fakeScanner") {
    group = "verification"
    description = "Starts the FakeScanner standalone TCP server on port 2323 by default (override with -PfakeScannerPort=<n>)."
    classpath = project.the<org.gradle.api.tasks.SourceSetContainer>().getByName("test").runtimeClasspath
    mainClass.set("dev.digiwomb.unboundair.scanner.FakeScannerMain")
    val publicPort = project.findProperty("fakeScannerPort")?.toString() ?: "2323"
    args("--port", publicPort)
    val page = project.findProperty("fakeScannerPage")?.toString()
    if (page != null) {
        args("--page", page)
    }
}

// Mutation testing (Spike B, docs/internal/entscheidungen.md). PIT is not wired into
// `build` or `check`; run it explicitly with `./gradlew pitest`. Versions are
// pinned: gradle-pitest-plugin 1.19.0 defaults to pitest 1.22.1, which predates
// the JUnit Platform 6 fix, so pitest 1.25.5 is set explicitly together with the
// matching pitest-junit5-plugin. The core packages are the mutation target; the
// scanner tests are timing-sensitive, so the per-test timeout is raised.
pitest {
    pitestVersion.set("1.25.5")
    junit5PluginVersion.set("1.2.2")
    // The core packages, as TE-04 defines them: where the risky logic lives.
    // Milestone 3 added `output` (PDF assembly) and `service` (loop and batch).
    // `config` stays out - a data class of defaults has nothing to mutate - and
    // so does `cli`, which only maps arguments onto commands.
    targetClasses.set(
        setOf(
            "dev.digiwomb.unboundair.scanner.*",
            "dev.digiwomb.unboundair.image.*",
            "dev.digiwomb.unboundair.processing.*",
            "dev.digiwomb.unboundair.output.*",
            // Named explicitly beside `output.*`: milestone 4 put the risky logic of
            // AU-04 and AU-05 into these two sub-packages, and the measurement basis
            // should say so rather than rely on how the glob reads.
            "dev.digiwomb.unboundair.output.outbox.*",
            "dev.digiwomb.unboundair.output.paperless.*",
            "dev.digiwomb.unboundair.service.*",
        ),
    )
    targetTests.set(
        setOf(
            "dev.digiwomb.unboundair.scanner.*",
            "dev.digiwomb.unboundair.image.*",
            "dev.digiwomb.unboundair.processing.*",
            "dev.digiwomb.unboundair.output.*",
            "dev.digiwomb.unboundair.output.outbox.*",
            "dev.digiwomb.unboundair.output.paperless.*",
            "dev.digiwomb.unboundair.service.*",
            // The page-log assertions live in `logging` but exercise `service`.
            "dev.digiwomb.unboundair.logging.*",
            // `e2e` stays out, measured: the layer's tests wait on Awaitility with a
            // 60-second ceiling, which is right for them and wrong here. A mutation
            // that breaks delivery makes every such test burn its full timeout
            // instead of failing fast. Measured over the same 8 mutations of
            // `OutputModules`: 4 min 53 s of mutation analysis against `e2e` versus
            // 1 s against `OutputModulesTest` -- roughly 37 seconds per mutation
            // against 0.13, a factor of ~290, which extrapolates to some 7 hours
            // for the full basis of 695. And e2e kills fewer: 6 of 8 against 7 of 8.
            // The mutations in `output` and `service` are covered by the unit, slice
            // and integration tests anyway; e2e adds runtime, not reach.
            // Numbers and method are written up in docs/internal/teststrategie.md.
        ),
    )
    outputFormats.set(setOf("HTML"))
    threads.set(1)
    timestampedReports.set(false)
    timeoutConstInMillis.set(60000)

    // Floor, not a target. 71 % is exactly what the re-measurement run over all
    // seven core packages measured (651/914 killed, 05.10.2026, commit c17b97a);
    // pinning it here makes a later drop in assertion quality fail the task
    // instead of passing unnoticed. The run raises the floor back from 66 % on
    // the same measurement basis -- no package was added, so the gain comes from
    // the sharpening sub-issues of #138 and not from a changed denominator.
    // Two of the three target packages still miss the ~70 % the parent issue
    // aims for: `service` 63 % (weakest: ScanLoop.kt at 53 %) and
    // `output.outbox` 57 % (Outbox.kt at 56 % with 91 % line coverage -- the
    // code runs, too little is asserted). That gap is tracked separately; it is
    // deliberately not closed along with #138.
    // Raise this number when the score improves; never lower it silently.
    // Per-package numbers and the weak spots are in docs/internal/entscheidungen.md.
    mutationThreshold.set(71)
}
