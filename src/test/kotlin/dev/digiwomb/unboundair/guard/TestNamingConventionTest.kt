package dev.digiwomb.unboundair.guard

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaCodeUnit
import com.tngtech.archunit.core.importer.ClassFileImporter
import dev.digiwomb.unboundair.image.EmptyGroupFixture
import dev.digiwomb.unboundair.image.TaggedButUnnamedFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Convention guard ("Konventionstest" of the Wächter layer in docs/teststrategie.md):
 * every test class in `dev.digiwomb.unboundair` must be traceable back to a requirement
 * from docs/plan.md.
 *
 * A class is a "test class" if its simple name ends with `Test` or `Tests`, or if it is
 * annotated with `@Nested` (JUnit test groups). A test class is compliant if either holds:
 *
 * 1. its simple name carries a requirement ID (`[A-Z]{2,4}-\d{2}`, e.g. `SC-01`, `SV-01`,
 *    `OF-06`); or
 * 2. it declares at least one `@Test` method, and **every** declared `@Test` method carries
 *    a requirement ID in its name.
 *
 * Rule 1 is the class-level form of the standard naming convention (backtick names with the
 * requirement ID); rule 2 is the usual case, where the class name does not carry an ID but
 * each test does. Helpers without `@Test` methods (e.g. `FakeScanner`, `TestImages`) do not
 * end in `Test`/`Tests` and are not `@Nested`, so they are not "test classes" and are
 * naturally exempt.
 *
 * **Two deliberate sharpenings** (review finding, this guard used to be evadable):
 *
 * - `@Tag` alone is **no longer** a free pass. Previously any class could opt out of the
 *   convention by carrying any `@Tag`, which made the rule unenforceable. The only exemption
 *   left is the `guard` package itself (see [isGuard]): those classes verify conventions
 *   rather than requirements, so they have no requirement ID to carry.
 * - A class with **no** `@Test` methods no longer passes vacuously. `all {}` over an empty
 *   list is `true` in Kotlin, so a `@Nested` group without its own tests used to slip
 *   through unchecked; rule 2 now demands at least one test method.
 *
 * The classes are read from the classpath with ArchUnit, which sees the compiled main and
 * test classes alike (same package namespace); the "test class" filter above isolates the
 * tests.
 */
@Tag("guard")
class TestNamingConventionTest {
    /**
     * Requirement IDs as used in docs/plan.md: two to four uppercase letters, a dash, two
     * digits. The match is partial because the ID sits at the start of a backtick name.
     */
    private val requirementId = Regex("[A-Z]{2,4}-\\d{2}")

    @Test
    fun `every test class is traceable to a requirement`() {
        val imported = ClassFileImporter().importPackages("dev.digiwomb.unboundair")
        val testClasses = imported.filter { it.isTestClass() }

        assertThat(testClasses)
            .`as`("the guard must find the existing test classes, otherwise it proves nothing")
            .isNotEmpty()

        // A grouping class carries no @Test method of its own; its cases live in @Nested inner
        // classes. Those count towards the outer class, otherwise a legitimate grouping class
        // (e.g. GrayscaleStepTest) would be rejected. Each nested group is still checked in its
        // own right, because the scan above picks it up too.
        val nestedByOuter =
            imported
                .filter { it.isAnnotatedWith(NESTED) }
                .groupBy { it.enclosingClassName() }

        val violators =
            testClasses
                .filter { !it.isCompliant(nestedByOuter[it.name].orEmpty()) }
                .map { it.simpleName }
                .sorted()

        assertThat(violators)
            .`as`(
                "every test class must carry a requirement ID (e.g. SC-01) in its name, or declare at " +
                    "least one @Test method with every such method carrying a requirement ID; only the " +
                    "guard package itself is exempt",
            ).isEmpty()
    }

    /**
     * Proves the sharpened rules actually bite. Without this, the two changes above would be
     * an untested claim: a rule that has never rejected anything is only an assertion.
     *
     * The fixtures deliberately live in `dev.digiwomb.unboundair.image` (a non-guard package),
     * because the guard package is exempt by design - fixtures inside it would be waved
     * through and the probe would prove nothing. They are imported explicitly by class, so the
     * package scan in the test above never sees them.
     */
    @Test
    fun `the convention rejects a tagged class without requirement IDs and an empty test group`() {
        val imported =
            ClassFileImporter().importClasses(
                TaggedButUnnamedFixture::class.java,
                EmptyGroupFixture::class.java,
            )

        val tagged = imported.single { it.simpleName == "TaggedButUnnamedFixture" }
        val emptyGroup = imported.single { it.simpleName == "EmptyGroupFixture" }

        assertThat(tagged.packageName)
            .`as`("the fixture must sit outside the guard package, otherwise the exemption hides the result")
            .isNotEqualTo(GUARD_PACKAGE)

        assertThat(tagged.isCompliant())
            .`as`("a @Tag without a requirement ID must be rejected - @Tag is no longer a free pass")
            .isFalse()

        assertThat(emptyGroup.isCompliant())
            .`as`("a test class without any @Test method must be rejected, not pass vacuously")
            .isFalse()
    }

    /**
     * A class is a test class if its simple name ends with `Test` or `Tests`, or if it is a
     * `@Nested` JUnit test group.
     */
    private fun JavaClass.isTestClass(): Boolean = simpleName.endsWith("Test") || simpleName.endsWith("Tests") || isAnnotatedWith(NESTED)

    /**
     * Compliance per the convention documented in the class KDoc.
     *
     * Note the two differences to the earlier version: `@Tag` is not accepted as a blanket
     * exemption (only the `guard` package is), and `isNotEmpty()` stops a class without any
     * `@Test` method from satisfying `all {}` vacuously.
     */
    private fun JavaClass.isCompliant(nested: List<JavaClass> = emptyList()): Boolean {
        if (isGuard()) return true
        if (requirementId.containsMatchIn(simpleName)) return true
        val tests = declaredTestMethods() + nested.flatMap { it.declaredTestMethods() }
        return tests.isNotEmpty() && tests.all { requirementId.containsMatchIn(it.name) }
    }

    /**
     * The guard classes themselves verify conventions, not requirements from docs/plan.md, so
     * they carry no requirement ID. The exemption is bound to the package rather than to an
     * annotation, so it cannot be claimed from anywhere else in the test tree.
     */
    private fun JavaClass.isGuard(): Boolean = packageName == GUARD_PACKAGE

    private fun JavaClass.declaredTestMethods() = methods.filter { it.isAnnotatedWith(TEST) }

    /**
     * Fully qualified name of the class this one is nested in, or `null` for a top-level class.
     * ArchUnit 1.5.0 returns this as an `Optional<JavaClass>`.
     */
    private fun JavaClass.enclosingClassName(): String? = enclosingClass.orElse(null)?.name

    /**
     * ArchUnit 1.5.0 exposes `isAnnotatedWith` on `JavaClass`, but not on `JavaMethod`; the
     * uniform check through `tryGetAnnotationOfType` works for both.
     */
    private fun JavaCodeUnit.isAnnotatedWith(annotation: String): Boolean = tryGetAnnotationOfType(annotation).isPresent

    private companion object {
        const val TEST = "org.junit.jupiter.api.Test"
        const val NESTED = "org.junit.jupiter.api.Nested"
        const val GUARD_PACKAGE = "dev.digiwomb.unboundair.guard"
    }
}
