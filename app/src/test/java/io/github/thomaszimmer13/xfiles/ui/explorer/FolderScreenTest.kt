package io.github.thomaszimmer13.xfiles.ui.explorer

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.test.core.app.ApplicationProvider
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Explorateur local, écran réel sur un vrai dossier (Lot 3) : une ligne = un seul nœud TalkBack
 * sans enfant, lecture « Nom, Type, Taille, Date », actions personnalisées, focus sur le voisin
 * après une suppression (audit M-06).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderScreenTest {

    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var dir: File

    @Before fun setUp() {
        dir = tmp.newFolder("Dossier")
        File(dir, "Photos").mkdirs()
        File(dir, "Photos/x.jpg").writeText("x")
        File(dir, "a.txt").writeText("a")
        File(dir, "b.txt").writeText("bb")
        File(dir, "c.txt").writeText("ccc")
    }

    private fun row(name: String): SemanticsNodeInteraction =
        compose.onNode(hasContentDescription("$name,", substring = true))

    private fun description(name: String): String =
        row(name).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()

    private fun actions(name: String): List<String> =
        row(name).fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }

    private fun show(viewModel: FolderViewModel) {
        compose.setContent {
            FolderScreen(path = dir.path, focusPath = null, onOpen = {}, onBack = {}, onJump = {}, rootLabel = "Explorer", viewModel = viewModel)
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("c.txt,", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun rowsAreSingleNodesReadInOrder() {
        show(FolderViewModel(app))
        for (name in listOf("a.txt", "b.txt", "c.txt", "Photos")) {
            // Aucun enfant : TalkBack ne s'arrête que sur la ligne elle-même.
            row(name).onChildren().assertCountEquals(0)
            // Le nom n'existe pas comme texte séparé, focalisable à part.
            compose.onAllNodes(hasText(name)).assertCountEquals(0)
        }
        // Nom → Type → Taille → Date.
        assertTrue(description("b.txt"), Regex("^b\\.txt, [^,]+, 2 octets, modifié .+").matches(description("b.txt")))
        assertTrue(description("Photos"), Regex("^Photos, dossier, 1 élément, modifié .+").matches(description("Photos")))
        assertTrue("1970" !in description("a.txt"))
    }

    @Test fun fileRowsCarryTheirActions() {
        show(FolderViewModel(app))
        val expected = listOf(
            R.string.action_open_with, R.string.action_share, R.string.action_rename, R.string.action_copy,
            R.string.action_move, R.string.action_duplicate, R.string.action_add_favorite, R.string.action_select,
            R.string.action_details, R.string.action_move_to_trash,
        ).map(app::getString)
        val file = actions("b.txt")
        assertTrue("manque ${expected - file.toSet()}", file.containsAll(expected))
        val folder = actions("Photos")
        val folderExpected = listOf(R.string.action_rename, R.string.action_details, R.string.action_move_to_trash, R.string.action_select).map(app::getString)
        assertTrue("manque ${folderExpected - folder.toSet()}", folder.containsAll(folderExpected))
    }

    @Test fun focusMovesToNeighbourAfterDeletion() {
        val viewModel = FolderViewModel(app)
        show(viewModel)
        val b = viewModel.state.value.items.single { it.name == "b.txt" }
        viewModel.deleteForever(b)
        val focused = SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)
        compose.waitUntil(10_000) { compose.onAllNodes(focused.and(hasContentDescription("c.txt,", substring = true))).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(focused).assertCountEquals(1)

        // Suppression du dernier : le précédent.
        val c = viewModel.state.value.items.single { it.name == "c.txt" }
        viewModel.deleteForever(c)
        compose.waitUntil(10_000) { compose.onAllNodes(focused.and(hasContentDescription("a.txt,", substring = true))).fetchSemanticsNodes().isNotEmpty() }
    }
}

/** Double panneau réel (audit M-10) : un seul nœud focalisé au départ, dans le panneau actif. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExplorerDualPaneTest {

    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    @Test fun initialFocusOnlyInActivePane() {
        val app = ApplicationProvider.getApplicationContext<XFilesApplication>()
        runBlocking { app.container.preferences.setDualPane(true) }
        val dir = tmp.newFolder("Musique")
        File(dir, "titre.mp3").writeText("x")
        compose.setContent { ExplorerScreen(openRequest = dir.path) }
        val focused = SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)
        compose.waitUntil(10_000) { compose.onAllNodes(focused).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val nodes = compose.onAllNodes(focused).fetchSemanticsNodes()
        assertTrue("nœuds focalisés : ${nodes.size}", nodes.size == 1)
        // Le titre du dossier ouvert dans le premier panneau, l'actif.
        val text = nodes.single().config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.joinToString()
        assertTrue(text, text.startsWith("Musique"))
        // Les deux panneaux sont bien affichés.
        compose.onAllNodes(hasText(app.getString(R.string.pane_second, ""), substring = true)).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }
}
