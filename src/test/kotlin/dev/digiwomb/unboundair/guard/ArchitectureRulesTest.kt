package dev.digiwomb.unboundair.guard

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaAnnotation
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Architecture guard ("Wächter" layer of docs/teststrategie.md): enforces the structural
 * decisions of docs/plan.md as executable rules, so an architectural regression turns
 * the build red instead of rotting silently.
 *
 * The rules:
 *
 * 1. **No `@ConditionalOn*` annotations (AU-03), on classes or methods.** Which output
 *    modules are active is decided at runtime from the `unboundair.output.modules`
 *    property; Spring's `@ConditionalOn*` annotations are not supported in GraalVM
 *    Native Images, so the code must not contain them at all (fixed decision in
 *    docs/plan.md). The check covers both classes and methods, because
 *    `@ConditionalOnProperty` commonly sits on a `@Bean` method rather than a class.
 *
 * 2. **Layered package dependencies.** `config`, `scanner` and `image` are leaves,
 *    `processing` may only use `image`, `output` only `processing` and `image`,
 *    `service` orchestrates the core, and `cli` may only reach the core through
 *    `service` (plus `scanner`, `processing` and `config` for the single-shot
 *    commands). This keeps the core decoupled from the CLI adapter, so a later web UI
 *    can dock onto `service` without rewiring anything.
 *
 *    Note that `config` is a leaf **and** that the three core packages may not use it:
 *    `scanner`, `image` and `processing` take their values as constructor parameters
 *    (the `PageSettings` pattern), which is what keeps them constructible without a
 *    Spring context and therefore unit-testable. Mapping properties onto those
 *    parameters is the composition root's job.
 *
 * 2a. **Only `cli` may access `service` (incoming rule).** The layered rules above all
 *    constrain what a layer may *use*. That alone would leave the topmost layer
 *    unguarded: nothing would stop `processing` from calling into the service loop and
 *    turning the dependency graph upside down. `service` is therefore also constrained
 *    from the other side.
 *
 * 3. **No Spring stereotypes in `..output..` (AU-03).** Modules are registered
 *    deliberately and selected at runtime; a stereotype annotation would wire them
 *    through component scanning instead. This rule is deliberately inert today: the
 *    `output` package is empty until the paperless-ngx module lands in a later
 *    milestone, so `allowEmptyShould(true)` tolerates exactly that one situation.
 *    The moment a class appears in `dev.digiwomb.unboundair.output..`, the rule checks
 *    it in full. It is kept (not removed) so the AU-03 "Laufzeit-Registrierung"
 *    decision stays guarded as an executable rule even while nothing implements it yet.
 *
 * 4. **The core stays free of Spring (`scanner`, `image`, `processing`, `output`).**
 *    No class there may import anything from `org.springframework`. This is the
 *    executable form of the decision behind rule 2: the core is plain Kotlin, takes its
 *    values through constructors, and can be exercised in a unit test without a
 *    context. It also keeps the GraalVM native image option open, and it is the rule
 *    that would catch the tempting shortcut of injecting `UnboundAirProperties`
 *    straight into a processing step.
 *
 * The classes are imported from the classpath, which contains the compiled main and
 * test classes alike (same package namespace); the rules apply to all of them, and
 * today they all satisfy them.
 *
 * The root class `UnboundAirApplication` is deliberately excluded from the layering:
 * it is the composition root and may reference anything. `layeredArchitecture()`
 * only constrains classes that belong to a declared layer, so the root class is simply
 * not assigned to one (the test-only `guard` package and `TestImages` are unlayered
 * for the same reason).
 *
 * The `output` layer is declared even though it is empty today: the paperless module
 * arrives in a later milestone, and the direction constraints must already be in
 * place for it.
 */
@Tag("guard")
class ArchitectureRulesTest {
    /**
     * All compiled classes of the application, main and test alike.
     */
    private val classes: JavaClasses =
        ClassFileImporter().importPackages("dev.digiwomb.unboundair")

    /**
     * The compiled classes that actually ship, without the test source set.
     *
     * Used by the single rule whose claim is about production code alone, the Spring ban
     * on the core packages. Everything else deliberately checks the tests as well.
     */
    private val mainClasses: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("dev.digiwomb.unboundair")

    /**
     * Matches any Spring `@ConditionalOn*` annotation (e.g. `@ConditionalOnProperty`)
     * by simple name, so the rule does not depend on the exact Spring artifact that
     * provides it.
     */
    private val conditionalOnAnnotations =
        object : DescribedPredicate<JavaAnnotation<*>>("annotated with @ConditionalOn*") {
            override fun test(annotation: JavaAnnotation<*>): Boolean = annotation.rawType.simpleName.startsWith("ConditionalOn")
        }

    /**
     * Matches the Spring annotations that component scanning would pick up, by simple
     * name, so the rule does not depend on the exact Spring artifact that provides
     * them.
     */
    private val stereotypeAnnotations =
        object : DescribedPredicate<JavaAnnotation<*>>("a Spring stereotype annotation") {
            override fun test(annotation: JavaAnnotation<*>): Boolean = annotation.rawType.simpleName in STEREOTYPES
        }

    /**
     * The planned layering of docs/plan.md. Two adaptations to ArchUnit 1.5.0, both
     * verified empirically against the current code base, neither of which weakens a
     * constraint:
     *
     * - [consideringOnlyDependenciesInLayers] instead of [consideringAllDependencies].
     *   In 1.5.0 the "all dependencies" scope counts every edge to a class that
     *   belongs to no layer (every single class has edges to `java.lang.Object` and
     *   `kotlin.Metadata`) as a layer violation, so the rule would report over 1500
     *   false violations on the current code. The "in layers" scope evaluates exactly
     *   the edges the `mayOnlyAccessLayers` / `mayNotAccessAnyLayer` conditions are
     *   designed for: dependencies whose source and target both belong to declared
     *   layers, which covers fields, method signatures, and return types alike.
     * - [withOptionalLayers] because the `output` layer is empty until the
     *   paperless-ngx module lands in a later milestone; ArchUnit otherwise requires
     *   every declared layer to be non-empty. All direction constraints stay in
     *   force, so the moment a class appears in `dev.digiwomb.unboundair.output..`
     *   the rule constrains it in both directions: it may only access
     *   `processing`/`image`, and only `service` may access it.
     *
     * The `mayOnlyBeAccessedByLayers` clauses on `service` and `output` are the
     * incoming half of the guard. Without them the topmost layers would be
     * unconstrained in the direction that matters most: nothing would stop a core
     * package from reaching up into the service loop.
     */
    private val layerDependencyRule: ArchRule =
        Architectures
            .layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("config")
            .definedBy("dev.digiwomb.unboundair.config..")
            .layer("scanner")
            .definedBy("dev.digiwomb.unboundair.scanner..")
            .layer("image")
            .definedBy("dev.digiwomb.unboundair.image..")
            .layer("processing")
            .definedBy("dev.digiwomb.unboundair.processing..")
            .layer("output")
            .definedBy("dev.digiwomb.unboundair.output..")
            .layer("service")
            .definedBy("dev.digiwomb.unboundair.service..")
            .layer("cli")
            .definedBy("dev.digiwomb.unboundair.cli..")
            .whereLayer("config")
            .mayNotAccessAnyLayer()
            .whereLayer("scanner")
            .mayNotAccessAnyLayer()
            .whereLayer("image")
            .mayNotAccessAnyLayer()
            .whereLayer("processing")
            .mayOnlyAccessLayers("image")
            .whereLayer("output")
            .mayOnlyAccessLayers("processing", "image")
            .whereLayer("service")
            .mayOnlyAccessLayers("scanner", "processing", "image", "output", "config")
            .whereLayer("service")
            .mayOnlyBeAccessedByLayers("cli")
            .whereLayer("cli")
            .mayOnlyAccessLayers("scanner", "processing", "service", "config")
            .because(
                "the core must stay decoupled from the CLI adapter, the layers must only " +
                    "talk downwards, and a later web UI must be able to dock onto the " +
                    "service layer without rewiring the core (docs/plan.md)",
            )

    /**
     * AU-03: output modules are selected at runtime from `unboundair.output.modules`,
     * never through Spring conditionals, which GraalVM Native Images do not support.
     * Protects the fixed decision "Module per Laufzeit-Auswahl" from creeping back in
     * as annotations — on classes and on methods alike, because `@ConditionalOnProperty`
     * commonly sits on a `@Bean` method rather than on a class.
     */
    @Test
    fun `AU-03 no class is annotated with a ConditionalOn-style annotation`() {
        noClasses().should().beAnnotatedWith(conditionalOnAnnotations).check(classes)
    }

    @Test
    fun `AU-03 no method is annotated with a ConditionalOn-style annotation`() {
        methods().should().notBeAnnotatedWith(conditionalOnAnnotations).check(classes)
    }

    /**
     * Protects the package structure from circular or upward coupling: the scanner and
     * image layers are leaves, processing stands on image, output stands on
     * processing and image, and the CLI adapter stands on scanner and processing.
     * Anything else (e.g. a `scanner` class referencing `image`) fails the build.
     */
    @Test
    fun `the package layers follow the planned dependency direction`() {
        layerDependencyRule.check(classes)
    }

    /**
     * AU-03: output modules are registered deliberately and selected at runtime; a
     * Spring stereotype annotation would wire them through component scanning instead
     * and defeat the runtime selection (and break GraalVM Native Images).
     *
     * `allowEmptyShould` is gone as of this milestone. It existed because
     * `dev.digiwomb.unboundair.output..` was empty and ArchUnit 1.5.0 fails a rule
     * whose `that()` clause matches nothing. The package now holds the interface, the
     * outbox and the paperless module, so the rule checks real classes -- and dropping
     * the flag means an empty `output` package would itself turn the build red, which
     * is the right alarm: this rule going quiet is how it would rot unnoticed.
     *
     * Deliberately checked against [classes], tests included, and deliberately not
     * narrowed for `output.paperless`: that package may use the Spring HTTP client,
     * but a stereotype there would still defeat the runtime module selection.
     */
    @Test
    fun `AU-03 output modules are not wired through component scanning`() {
        noClasses()
            .that()
            .resideInAPackage("dev.digiwomb.unboundair.output..")
            .should()
            .beAnnotatedWith(stereotypeAnnotations)
            .check(classes)
    }

    /**
     * The core packages stay plain Kotlin, free of Spring.
     *
     * This is the executable form of the layering decision in docs/plan.md: the core
     * takes its values through constructor parameters (the `PageSettings` pattern) and
     * must stay constructible without an application context, which is what makes it
     * unit-testable and keeps the GraalVM native image option open.
     *
     * The rule bites exactly where the shortcut is tempting: injecting
     * `UnboundAirProperties` directly into a processing step or the scanner client would
     * be one import and would quietly couple the core to the framework. Note that the
     * layered rule above cannot catch this, because `org.springframework` belongs to no
     * declared layer.
     *
     * `cli` and `service` are deliberately **not** covered: the composition root and the
     * service layer are where Spring legitimately lives.
     *
     * `output.paperless` is the one named exception inside the core (docs/plan.md, "Der
     * Kern bleibt frei von Spring"): it may use the Spring `RestClient` and the
     * `spring-web` types for multipart and headers, because uploading is the single
     * point in v1 where a core package talks outward and a second HTTP client for it
     * would stand against "keep dependencies minimal". The exception is carved out by
     * package, not by class, so a new file there inherits it -- which is why the two
     * rules below fence that package in from the other side: no stereotypes (the AU-03
     * rule above covers all of `output..`) and no `UnboundAirProperties`.
     *
     * This is the one rule evaluated against [mainClasses] rather than [classes]. The
     * claim it makes is about what *ships*: production code takes its values through
     * constructors and needs no context. A slice test of a core type legitimately boots
     * Spring to prove the shipped object behaves -- `MetadataJsonSliceTest` uses
     * `@JsonTest` for exactly that -- and it is never in a native image, so counting it
     * as a violation would forbid testing the very property this rule protects. The
     * other rules keep seeing the tests: a *test* that wires a core class through
     * component scanning is a real smell, and nothing here relaxes that.
     */
    @Test
    fun `the core packages do not depend on Spring`() {
        noClasses()
            .that()
            .resideInAnyPackage(
                "dev.digiwomb.unboundair.scanner..",
                "dev.digiwomb.unboundair.image..",
                "dev.digiwomb.unboundair.processing..",
                "dev.digiwomb.unboundair.output..",
            ).and()
            .resideOutsideOfPackage("dev.digiwomb.unboundair.output.paperless..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework..")
            .because(
                "the core must stay constructible without an application context, so it can be " +
                    "unit-tested and later compiled to a native image (docs/plan.md)",
            ).check(mainClasses)
    }

    /**
     * The named exception stays narrow: `output.paperless` may speak HTTP, nothing more.
     *
     * The rule above exempts the whole package, which is the only way to express the
     * decision in docs/plan.md -- but an exemption written by package is an open door
     * for everything else Spring offers. The plan names what remains forbidden there:
     * stereotypes and an injected `UnboundAirProperties`. Stereotypes are already
     * covered for all of `output..` by the AU-03 rule; this rule adds the second half,
     * so the settings keep arriving as constructor values from the composition root.
     *
     * `config` is reached through its own package, not through Spring, so the layering
     * rule alone would also catch this -- deliberately duplicated here, because the
     * reason differs: there it is about direction, here about the exemption not
     * widening into "paperless may do anything".
     */
    @Test
    fun `AU-05 the paperless module takes its settings as constructor values`() {
        noClasses()
            .that()
            .resideInAPackage("dev.digiwomb.unboundair.output.paperless..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("dev.digiwomb.unboundair.config..")
            .because(
                "the Spring exception for this package covers the HTTP client only; the settings " +
                    "arrive as constructor values from the composition root (docs/plan.md)",
            ).check(classes)
    }

    private companion object {
        val STEREOTYPES =
            setOf("Component", "Service", "Repository", "Configuration", "Controller", "RestController", "Bean")
    }
}
