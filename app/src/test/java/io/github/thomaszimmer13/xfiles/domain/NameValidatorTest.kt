package io.github.thomaszimmer13.xfiles.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class NameValidatorTest {

    private val existing = listOf("Photos", "Facture.pdf", "Nouveau dossier")

    private fun problem(name: String, current: String? = null) =
        (NameValidator.check(name, existing, current) as? NameCheck.Invalid)?.problem

    @Test fun valid() {
        assertEquals(NameCheck.Valid("Scripts"), NameValidator.check("  Scripts ", existing))
    }

    @Test fun problems() {
        assertEquals(NameProblem.EMPTY, problem("   "))
        assertEquals(NameProblem.RESERVED, problem(".."))
        assertEquals(NameProblem.FORBIDDEN_CHARACTERS, problem("a/b"))
        assertEquals(NameProblem.FORBIDDEN_CHARACTERS, problem("note: test"))
        assertEquals(NameProblem.TOO_LONG, problem("é".repeat(130)))
        assertEquals(NameProblem.ALREADY_EXISTS, problem("photos"))
    }

    @Test fun rename() {
        assertEquals(NameCheck.Unchanged, NameValidator.check("Photos", existing, current = "Photos"))
        // Changer seulement la casse de son propre nom est permis.
        assertEquals(NameCheck.Valid("PHOTOS"), NameValidator.check("PHOTOS", existing, current = "Photos"))
        assertEquals(NameProblem.ALREADY_EXISTS, problem("facture.PDF", current = "Photos"))
    }

    @Test fun uniqueName() {
        assertEquals("Nouveau dossier 2", NameValidator.uniqueName("Nouveau dossier", existing))
        assertEquals("Scripts", NameValidator.uniqueName("Scripts", existing))
    }
}
