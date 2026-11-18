package io.github.thomaszimmer13.xfiles.ui.explorer

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Double panneau de l'Explorer (audit M-10) : chaque panneau fournit ses PaneActions, et seul le
 * panneau actif place le focus initial ; le panneau inactif ne le vole jamais.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DualPaneFocusTest {

    @get:Rule val compose = createComposeRule()

    /** Écran d'un panneau, comme FolderScreen : focus initial sur son titre une fois chargé. */
    @Composable
    private fun PaneScreen(title: String) {
        val titleFocus = remember { FocusRequester() }
        FocusOnceEffect(ready = true) { titleFocus.requestFocus() }
        XFilesTopBar(title = title, spokenTitle = title, titleFocus = titleFocus)
    }

    private val focused = SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)

    @Test fun onlyTheActivePaneTakesInitialFocus() {
        var active by mutableIntStateOf(1)
        var opened by mutableIntStateOf(0)
        compose.setContent {
            Column {
                for (index in 0..1) {
                    CompositionLocalProvider(LocalPaneActions provides PaneActions(null, null, isActive = active == index)) {
                        // Changer de dossier dans un panneau : nouvel écran, nouveau focus initial.
                        key(if (index == active) opened else -1) { PaneScreen("Panneau ${index + 1}, écran $opened") }
                    }
                }
            }
        }
        compose.waitForIdle()
        // Le second panneau, actif, a le focus ; le premier, composé avant lui, ne l'a pas pris.
        compose.onNodeWithContentDescription("Panneau 2", substring = true).assertIsFocused()
        compose.onNodeWithContentDescription("Panneau 1", substring = true).assertIsNotFocused()
        compose.onAllNodes(focused).assertCountEquals(1)

        // Le premier panneau devient actif et ouvre un dossier : il prend le focus, seul.
        active = 0
        opened = 1
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Panneau 1, écran 1").assertIsFocused()
        compose.onAllNodes(focused).assertCountEquals(1)
    }
}
