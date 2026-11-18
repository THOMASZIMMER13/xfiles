package io.github.thomaszimmer13.xfiles.data.rename

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BatchRenameTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sources(vararg names: String) = names.map { RenameSource("/d/$it", it, 0L, false) }

    private fun names(plan: List<RenamePlanItem>) = plan.map { it.newName }

    @Test fun replaceKeepsExtension() {
        val plan = BatchRename.plan(sources("IMG_001.JPG", "IMG_002.JPG", "notes.txt"), emptyList(), RenameRules(find = "img_", replace = "Vacances "))
        assertEquals(listOf("Vacances 001.JPG", "Vacances 002.JPG", "notes.txt"), names(plan))
        assertEquals(listOf(RenameStatus.RENAMED, RenameStatus.RENAMED, RenameStatus.UNCHANGED), plan.map { it.status })
    }

    @Test fun regexWithGroups() {
        val plan = BatchRename.plan(sources("2026-11-18 scan.pdf"), emptyList(), RenameRules(find = "(\\d{4})-(\\d{2})-(\\d{2})", replace = "$3.$2.$1", regex = true))
        assertEquals(listOf("18.11.2026 scan.pdf"), names(plan))
        val bad = BatchRename.plan(sources("a.txt"), emptyList(), RenameRules(find = "(", regex = true))
        assertEquals(RenameStatus.BAD_PATTERN, bad.single().status)
    }

    @Test fun templateNumbering() {
        val plan = BatchRename.plan(sources(*Array(12) { "p$it.jpg" }), emptyList(), RenameRules(mode = RenameMode.TEMPLATE, template = "Vacances {n}"))
        assertEquals("Vacances 01.jpg", plan.first().newName)
        assertEquals("Vacances 12.jpg", plan.last().newName)
        val three = BatchRename.plan(sources("a.jpg"), emptyList(), RenameRules(mode = RenameMode.TEMPLATE, template = "{n} - {nom}", start = 7, digits = 3))
        assertEquals("007 - a.jpg", three.single().newName)
    }

    @Test fun caseChanges() {
        val r = RenameRules(case = CaseChange.WORDS)
        assertEquals("Rapport De Stage.PDF", BatchRename.plan(sources("RAPPORT DE STAGE.PDF"), emptyList(), r).single().newName)
        assertEquals("rapport.pdf", BatchRename.plan(sources("RAPPORT.pdf"), emptyList(), RenameRules(case = CaseChange.LOWER, includeExtension = true)).single().newName)
    }

    @Test fun conflicts() {
        val r = RenameRules(mode = RenameMode.TEMPLATE, template = "même")
        assertEquals(setOf(RenameStatus.DUPLICATE), BatchRename.plan(sources("a.txt", "b.txt"), emptyList(), r).map { it.status }.toSet())
        // « Même.txt » existe déjà dans le dossier (insensible à la casse).
        assertEquals(RenameStatus.EXISTS, BatchRename.plan(sources("a.txt"), listOf("a.txt", "Même.txt"), r).single().status)
        // Échange de noms entre éléments renommés : permis.
        val swap = BatchRename.plan(sources("a.txt", "b.txt"), listOf("a.txt", "b.txt"), RenameRules(mode = RenameMode.TEMPLATE, template = "x{n}"))
        assertTrue(swap.all { it.status == RenameStatus.RENAMED })
        assertEquals(RenameStatus.INVALID, BatchRename.plan(sources("a.txt"), emptyList(), RenameRules(find = "a", replace = "b:c")).single().status)
    }

    @Test fun applySwapAndUndo() {
        val a = File(tmp.root, "a.txt").apply { writeText("A") }
        val b = File(tmp.root, "b.txt").apply { writeText("B") }
        val sources = listOf(a, b).map { RenameSource(it.path, it.name, it.lastModified(), false) }
        // a → b et b → a en même temps.
        val plan = listOf(RenamePlanItem(sources[0], "b.txt", RenameStatus.RENAMED), RenamePlanItem(sources[1], "a.txt", RenameStatus.RENAMED))
        val done = BatchRename.apply(plan)
        assertEquals("B", File(tmp.root, "a.txt").readText())
        assertEquals("A", File(tmp.root, "b.txt").readText())
        BatchRename.undo(done)
        assertEquals("A", File(tmp.root, "a.txt").readText())
        assertEquals(listOf("a.txt", "b.txt"), tmp.root.list()!!.sorted())
    }
}
