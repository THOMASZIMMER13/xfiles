package io.github.thomaszimmer13.xfiles.ui.remote

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.data.remote.RemoteEntry
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import io.github.thomaszimmer13.xfiles.ui.explorer.FocusRequest
import io.github.thomaszimmer13.xfiles.ui.explorer.neighborFocus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Dossier distant (audit M-06) : focus sur le voisin après une suppression, sur le titre quand le
 * dossier devient vide, et actions personnalisées de chaque ligne.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RemoteFolderScreenTest {

    @get:Rule val compose = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun entry(name: String, dir: Boolean = false) = RemoteEntry(name, "/Docs/$name", dir, if (dir) 0 else 1000, 0)

    private val noActions = { _: RemoteEntry -> EntryActions({}, {}, {}, {}, {}, {}, {}) }

    @Test fun focusGoesToNeighbourThenTitle() {
        var entries by mutableStateOf(listOf(entry("a.txt"), entry("b.txt"), entry("c.txt")))
        var request by mutableStateOf<FocusRequest?>(null)
        compose.setContent {
            val titleFocus = remember { FocusRequester() }
            val itemFocus = remember { FocusRequester() }
            val listState = rememberLazyListState()
            var focusedPath by remember { mutableStateOf<String?>(null) }
            Column {
                XFilesTopBar(title = "Docs", spokenTitle = "Docs, dossier", titleFocus = titleFocus)
                RemoteFocusEffect(entries, request, listState, itemFocus, titleFocus, { focusedPath = it }) { request = null }
                RemoteEntryList(entries, listState, itemFocus, focusedPath, actionsFor = noActions)
            }
        }

        // Suppression de b.txt : le focus va au suivant, c.txt.
        val afterB = neighborFocus(entries, "/Docs/b.txt") { it.path }
        assertEquals(FocusRequest.Item("/Docs/c.txt"), afterB)
        entries = entries.filter { it.name != "b.txt" }
        request = afterB
        compose.waitForIdle()
        compose.onNodeWithContentDescription("c.txt", substring = true).assertIsFocused()
        assertNull(request)

        // Suppression du dernier : le précédent, a.txt.
        val afterC = neighborFocus(entries, "/Docs/c.txt") { it.path }
        entries = entries.filter { it.name != "c.txt" }
        request = afterC
        compose.waitForIdle()
        compose.onNodeWithContentDescription("a.txt", substring = true).assertIsFocused()

        // Plus rien : le titre.
        val afterA = neighborFocus(entries, "/Docs/a.txt") { it.path }
        assertEquals(FocusRequest.Title, afterA)
        entries = emptyList()
        request = afterA
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Docs, dossier").assertIsFocused()
        // Un seul élément focalisé à la fois.
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).assertCountEquals(1)
    }

    @Test fun rowsCarryCustomActions() {
        compose.setContent {
            RemoteEntryList(
                entries = listOf(entry("Photos", dir = true), entry("rapport.pdf")),
                listState = rememberLazyListState(),
                itemFocus = remember { FocusRequester() },
                focusedPath = null,
            ) { e -> EntryActions({}, {}, if (e.isDirectory) null else ({}), {}, {}, {}, {}) }
        }
        fun labels(name: String) = compose.onNodeWithContentDescription(name, substring = true)
            .fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }

        val download = app.getString(R.string.remote_download)
        val share = app.getString(R.string.action_share)
        val rename = app.getString(R.string.action_rename)
        val details = app.getString(R.string.action_details)
        val copyPath = app.getString(R.string.action_copy_path)
        val delete = app.getString(R.string.remote_delete)
        assertEquals(listOf(download, share, rename, details, copyPath, delete), labels("rapport.pdf"))
        // Un dossier ne se partage pas.
        assertEquals(listOf(download, rename, details, copyPath, delete), labels("Photos"))
        // Taille connue, date inconnue : jamais « 1970 ».
        val spoken = compose.onNodeWithContentDescription("rapport.pdf", substring = true)
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertEquals(false, "1970" in spoken)
    }

    @Test fun childTowardFindsTheFolderWeCameFrom() {
        assertEquals("/Docs", remoteChildToward("/", "/Docs/2024/a.pdf"))
        assertEquals("/Docs/2024", remoteChildToward("/Docs", "/Docs/2024"))
        assertNull(remoteChildToward("/Docs", "/Docsx/a"))
        assertNull(remoteChildToward("/Docs", null))
        assertNull(remoteChildToward("/", "/"))
    }
}
