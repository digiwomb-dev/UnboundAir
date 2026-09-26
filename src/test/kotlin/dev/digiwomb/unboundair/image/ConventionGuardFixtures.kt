package dev.digiwomb.unboundair.image

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

// Fixtures for the negative probe in TestNamingConventionTest (guard package). They exist only
// to be rejected by the convention guard, proving its two sharpened rules actually bite.
//
// They deliberately live outside the `guard` package, because that package is exempt from the
// convention by design - a fixture inside it would be waved through and the probe would prove
// nothing.
//
// Both classes are named `...Fixture`, not `...Test`, so the guard's own package scan and the
// JUnit discovery ignore them; the probe imports them explicitly by class.

/**
 * Carries a `@Tag` but no requirement ID - neither in the class name nor in the method name.
 * Under the old rule the `@Tag` alone made this compliant, which is exactly the loophole the
 * review found.
 */
@Tag("not-a-free-pass")
internal class TaggedButUnnamedFixture {
    @Test
    fun `no requirement id in this name`() = Unit
}

/**
 * Declares no `@Test` method at all. Under the old rule `all {}` over the empty method list
 * was vacuously `true`, so a `@Nested` group without its own tests passed unchecked.
 */
internal class EmptyGroupFixture
