package dev.digiwomb.unboundair.guard

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaCodeUnit
import com.tngtech.archunit.core.importer.ClassFileImporter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Convention guard ("Konventionstest" of the Wächter layer in docs/teststrategie.md):
 * every test class in `dev.digiwomb.unboundair` must be traceable back to a requirement
 * from docs/plan.md.
 *
 * A class is a "test class" if its simple name ends with `Test` or `Tests`, or if it is
 * annotated with `@Nested` (JUnit test groups). A test class is compliant if any of the
 * following holds:
 *
 * 1. its simple name carries a requirement ID (`[A-Z]{2,4}-\d{2}`, e.g. `SC-01`, `SV-01`,
 *    `OF-06`);
 * 2. it is annotated with `@Tag`; or
 * 3. every `@Test` method it declares carries a requirement ID in its name, or the method
 *    or the class is annotated with `@Tag`.
 *
 * Rule 1 is the class-level form of the standard naming convention (backtick names with the
 * requirement ID); rule 3 is the usual case, where the class name does not carry an ID but
 * each test does. Helpers without `@Test` methods (e.g. `FakeScanner`, `TestImages`) do not
 * end in `Test`/`Tests` and are not `@Nested`, so they are not "test classes" and are
 * naturally exempt.
 *
 * The guard itself carries no requirement ID in its name, so it relies on rule 2:
 * `@Tag("guard")`.
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
    fun `every test class is traceable to a requirement or tagged`() {
        val testClasses =
            ClassFileImporter()
                .importPackages("dev.digiwomb.unboundair")
                .filter { it.isTestClass() }

        assertThat(testClasses)
            .`as`("the guard must find the existing test classes, otherwise it proves nothing")
            .isNotEmpty()

        val violators = testClasses.filter { !it.isCompliant() }.map { it.simpleName }.sorted()

        assertThat(violators)
            .`as`(
                "every test class must carry a requirement ID (e.g. SC-01) in its name, be annotated " +
                    "with @Tag, or have every @Test method carry a requirement ID or a @Tag",
            ).isEmpty()
    }

    /**
     * A class is a test class if its simple name ends with `Test` or `Tests`, or if it is a
     * `@Nested` JUnit test group.
     */
    private fun JavaClass.isTestClass(): Boolean = simpleName.endsWith("Test") || simpleName.endsWith("Tests") || isAnnotatedWith(NESTED)

    /**
     * Compliance per the convention documented in the class KDoc.
     */
    private fun JavaClass.isCompliant(): Boolean =
        requirementId.containsMatchIn(simpleName) ||
            isAnnotatedWith(TAG) ||
            declaredTestMethods().all { requirementId.containsMatchIn(it.name) || it.isAnnotatedWith(TAG) }

    private fun JavaClass.declaredTestMethods() = methods.filter { it.isAnnotatedWith(TEST) }

    /**
     * ArchUnit 1.5.0 exposes `isAnnotatedWith` on `JavaClass`, but not on `JavaMethod`; the
     * uniform check through `tryGetAnnotationOfType` works for both.
     */
    private fun JavaCodeUnit.isAnnotatedWith(annotation: String): Boolean = tryGetAnnotationOfType(annotation).isPresent

    private companion object {
        const val TEST = "org.junit.jupiter.api.Test"
        const val TAG = "org.junit.jupiter.api.Tag"
        const val NESTED = "org.junit.jupiter.api.Nested"
    }
}
