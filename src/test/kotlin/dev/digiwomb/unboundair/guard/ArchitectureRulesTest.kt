package dev.digiwomb.unboundair.guard

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaAnnotation
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchRule
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
 * 1. **No `@ConditionalOn*` annotations (AU-03).** Which output modules are active is
 *    decided at runtime from the `unboundair.output.modules` property; Spring's
 *    `@ConditionalOn*` annotations are not supported in GraalVM Native Images, so the
 *    code must not contain them at all (fixed decision in docs/plan.md).
 *
 * 2. **Layered package dependencies.** `scanner` and `image` are leaves, `processing`
 *    may only use `image`, `output` only `processing` and `image`, and `cli` only
 *    `scanner` and `processing`. This keeps the core (scanner, processing, output)
 *    decoupled from the CLI adapter and from each other, so a later web UI can dock
 *    without rewiring the core.
 *
 * 3. **No Spring stereotypes in `..output..` (AU-03).** Modules are registered
 *    deliberately and selected at runtime; a stereotype annotation would wire them
 *    through component scanning instead.
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
     *   every declared layer to be non-empty. All five direction constraints stay in
     *   force, so the moment a class appears in `dev.digiwomb.unboundair.output..`
     *   the rule constrains it in both directions: it may only access
     *   `processing`/`image`, and no other layer may access it.
     */
    private val layerDependencyRule: ArchRule =
        Architectures
            .layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("scanner")
            .definedBy("dev.digiwomb.unboundair.scanner..")
            .layer("image")
            .definedBy("dev.digiwomb.unboundair.image..")
            .layer("processing")
            .definedBy("dev.digiwomb.unboundair.processing..")
            .layer("output")
            .definedBy("dev.digiwomb.unboundair.output..")
            .layer("cli")
            .definedBy("dev.digiwomb.unboundair.cli..")
            .whereLayer("scanner")
            .mayNotAccessAnyLayer()
            .whereLayer("image")
            .mayNotAccessAnyLayer()
            .whereLayer("processing")
            .mayOnlyAccessLayers("image")
            .whereLayer("output")
            .mayOnlyAccessLayers("processing", "image")
            .whereLayer("cli")
            .mayOnlyAccessLayers("scanner", "processing")
            .because(
                "the core must stay decoupled from the CLI adapter and the layers must " +
                    "only talk to the layer directly below them (docs/plan.md)",
            )

    /**
     * AU-03: output modules are selected at runtime from `unboundair.output.modules`,
     * never through Spring conditionals, which GraalVM Native Images do not support.
     * Protects the fixed decision "Module per Laufzeit-Auswahl" from creeping back in
     * as annotations.
     */
    @Test
    fun `AU-03 no class is annotated with a ConditionalOn-style annotation`() {
        noClasses().should().beAnnotatedWith(conditionalOnAnnotations).check(classes)
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
     * [ArchRule.allowEmptyShould] is set because `dev.digiwomb.unboundair.output..`
     * is empty until the paperless-ngx module lands in a later milestone, and
     * ArchUnit 1.5.0 fails a rule whose `that()` clause matches nothing (its
     * default `failOnEmptyShould` is `true`). This tolerates exactly that one
     * situation: today the rule checks zero classes, and the moment a class
     * appears in the `output` package it is checked in full.
     */
    @Test
    fun `AU-03 output modules are not wired through component scanning`() {
        noClasses()
            .that()
            .resideInAPackage("dev.digiwomb.unboundair.output..")
            .should()
            .beAnnotatedWith(stereotypeAnnotations)
            .allowEmptyShould(true)
            .check(classes)
    }

    private companion object {
        val STEREOTYPES =
            setOf("Component", "Service", "Repository", "Configuration", "Controller", "RestController", "Bean")
    }
}
