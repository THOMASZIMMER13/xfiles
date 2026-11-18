package io.github.thomaszimmer13.xfiles.ui.a11y

import android.app.Application
import android.app.KeyguardManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.data.source.FileItem
import io.github.thomaszimmer13.xfiles.data.transfer.ConflictPrompt
import io.github.thomaszimmer13.xfiles.data.trash.TrashEntry
import io.github.thomaszimmer13.xfiles.ui.components.rememberFileLauncher
import io.github.thomaszimmer13.xfiles.ui.explorer.ConflictDialog
import io.github.thomaszimmer13.xfiles.ui.explorer.TabStrip
import io.github.thomaszimmer13.xfiles.ui.favorites.Favorite
import io.github.thomaszimmer13.xfiles.ui.favorites.FavoriteRow
import io.github.thomaszimmer13.xfiles.ui.search.SearchScreen
import io.github.thomaszimmer13.xfiles.ui.search.SearchViewModel
import io.github.thomaszimmer13.xfiles.ui.security.LockScreen
import io.github.thomaszimmer13.xfiles.ui.trash.TrashItem
import io.github.thomaszimmer13.xfiles.ui.trash.TrashRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Lot 3 : structure TalkBack des dialogues, de la barre d'onglets et des lignes des favoris,
 * de la recherche et de la corbeille.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreensAccessibilityTest {

    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun s(id: Int) = app.getString(id)
    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
    private fun role(r: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, r)

    private fun customActions(description: String): List<String> =
        compose.onNode(hasContentDescription(description, substring = true))
            .fetchSemanticsNode().config.getOrElse(SemanticsActions.CustomActions) { emptyList() }.map { it.label }

    // --- Dialogues ---

    @Test fun conflictDialog() {
        var answered: String? = null
        compose.setContent {
            ConflictDialog(ConflictPrompt("Facture.pdf", false, "Documents", othersRemaining = 2)) { answered = it.choice?.name }
        }
        compose.onNodeWithText(s(R.string.conflict_title)).assert(heading)
        for (label in listOf(R.string.conflict_replace, R.string.conflict_keep_both, R.string.conflict_skip, R.string.action_cancel_paste)) {
            compose.onNodeWithText(s(label)).assertHasClickAction()
        }
        // « Appliquer à tous » : une case à cocher, un seul élément.
        compose.onNode(role(Role.Checkbox)).assertHasClickAction()
        compose.onAllNodes(role(Role.Checkbox)).assertCountEquals(1)
        compose.onNodeWithText(s(R.string.conflict_skip)).performClickAction()
        assertEquals("SKIP", answered)
    }

    @Test fun conflictDialogWithoutOthersHasNoCheckbox() {
        compose.setContent { ConflictDialog(ConflictPrompt("a.txt", false, "Documents", othersRemaining = 0)) {} }
        compose.onAllNodes(role(Role.Checkbox)).assertCountEquals(0)
    }

    @Test fun lockScreenWithDeviceLock() {
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(true)
        compose.setContent { LockScreen(onUnlocked = {}, onDisable = {}) }
        compose.onNodeWithText(s(R.string.lock_title)).assert(heading)
        compose.onNodeWithText(s(R.string.lock_unlock)).assertHasClickAction()
    }

    @Test fun lockScreenWithoutDeviceLock() {
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(false)
        var disabled = false
        compose.setContent { LockScreen(onUnlocked = {}, onDisable = { disabled = true }) }
        compose.onNodeWithText(s(R.string.lock_title)).assert(heading)
        compose.onNodeWithText(s(R.string.lock_disable_open)).assertHasClickAction().performClickAction()
        assertTrue(disabled)
    }

    // --- Barre d'onglets ---

    @Test fun tabStrip() {
        var ids by mutableStateOf(listOf(1, 2, 3))
        compose.setContent {
            TabStrip(ids = ids, current = 2, label = { "Onglet $it" }, onSelect = {}, onClose = { id -> ids = ids - id }, onNew = {})
        }
        compose.onAllNodes(role(Role.Tab)).assertCountEquals(3)
        val second = compose.onNode(hasText("Onglet 2").and(role(Role.Tab)), useUnmergedTree = false)
        second.assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        val info = second.fetchSemanticsNode().config[SemanticsProperties.CollectionItemInfo]
        assertEquals(1, info.columnIndex)
        assertEquals(listOf(s(R.string.tab_close)), second.fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label })
        // Fermer l'onglet par l'action personnalisée.
        compose.onNode(hasText("Onglet 3").and(role(Role.Tab))).fetchSemanticsNode().config[SemanticsActions.CustomActions].single().action()
        compose.waitForIdle()
        compose.onAllNodes(role(Role.Tab)).assertCountEquals(2)
        // Un seul onglet : plus d'action « Fermer l'onglet ».
        ids = listOf(1)
        compose.waitForIdle()
        val only = compose.onNode(role(Role.Tab)).fetchSemanticsNode().config
        assertFalse(only.contains(SemanticsActions.CustomActions))
    }

    // --- Lignes ---

    @Test fun favoriteRows() {
        val file = FileItem("/s/Documents/Facture.pdf", "Facture.pdf", false, 2_000, 0, null)
        val folder = FileItem("/s/Musique", "Musique", true, 0, 0, 4)
        compose.setContent {
            val launcher = rememberFileLauncher(onRoute = {}, onProblem = {})
            androidx.compose.foundation.layout.Column {
                FavoriteRow(Favorite(folder.path, "Musique", folder, listOf("Mémoire interne")), 0, 3, null, { _, _, _ -> }, {}, {}, { _, _ -> }, launcher)
                FavoriteRow(Favorite(file.path, "Facture.pdf", file, listOf("Mémoire interne", "Documents")), 1, 3, null, { _, _, _ -> }, {}, {}, { _, _ -> }, launcher)
                FavoriteRow(Favorite("/s/Perdu", "Perdu", null, emptyList()), 2, 3, null, { _, _, _ -> }, {}, {}, { _, _ -> }, launcher)
            }
        }
        val reveal = s(R.string.action_reveal)
        val up = s(R.string.action_move_up)
        val down = s(R.string.action_move_down)
        val remove = s(R.string.action_remove_favorite)
        assertEquals(listOf(reveal, down, remove), customActions("Musique, dossier"))
        assertEquals(listOf(reveal, s(R.string.action_open_with), s(R.string.action_share), up, down, remove), customActions("Facture.pdf, document PDF"))
        // Favori disparu : seulement le monter et le retirer.
        assertEquals(listOf(up, remove), customActions("Perdu"))
        // Lecture : nom, type, taille ; date inconnue jamais lue.
        val spoken = compose.onNode(hasContentDescription("Facture.pdf,", substring = true)).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue(spoken, spoken.startsWith("Facture.pdf, document PDF, 2 kilooctets"))
        assertFalse(spoken, "1970" in spoken)
        compose.onNode(hasContentDescription("Facture.pdf,", substring = true)).onChildren().assertCountEquals(0)
    }

    @Test fun searchResultRows() {
        val dir = tmp.newFolder("Docs")
        File(dir, "rapport annuel.pdf").writeText("x")
        File(dir, "Rapports").mkdirs()
        val viewModel = SearchViewModel(app)
        compose.setContent { SearchScreen(scope = dir.path, onOpen = {}, onReveal = {}, onBack = {}, viewModel = viewModel) }
        compose.waitForIdle()
        viewModel.setQuery("rapport")
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("rapport annuel.pdf,", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        val reveal = s(R.string.action_reveal)
        assertEquals(listOf(reveal, s(R.string.action_open_with), s(R.string.action_share)), customActions("rapport annuel.pdf,"))
        assertEquals(listOf(reveal), customActions("Rapports, dossier"))
        compose.onNode(hasContentDescription("rapport annuel.pdf,", substring = true)).onChildren().assertCountEquals(0)
    }

    @Test fun trashRow() {
        val entry = TrashEntry("1-a", "/s/.xfiles-corbeille", "/s/Documents/Facture.pdf", "Facture.pdf", false, 2_000, null, System.currentTimeMillis())
        compose.setContent {
            TrashRow(TrashItem(entry, listOf("Mémoire interne", "Documents")), 0, 1, null, {}, {}, {})
        }
        assertEquals(listOf(s(R.string.action_restore), s(R.string.action_delete_forever)), customActions("Facture.pdf,"))
        val spoken = compose.onNode(hasContentDescription("Facture.pdf,", substring = true)).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue(spoken, Regex("^Facture\\.pdf, document PDF, 2 kilooctets, supprimé aujourd'hui à .+").containsMatchIn(spoken))
        compose.onNode(hasContentDescription("Facture.pdf,", substring = true)).onChildren().assertCountEquals(0)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickAction() {
    fetchSemanticsNode().config[SemanticsActions.OnClick].action?.invoke()
}
