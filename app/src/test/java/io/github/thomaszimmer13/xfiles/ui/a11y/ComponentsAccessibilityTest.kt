package io.github.thomaszimmer13.xfiles.ui.a11y

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.ui.components.LocalAnnouncePosition
import io.github.thomaszimmer13.xfiles.ui.components.ShowPasswordRow
import io.github.thomaszimmer13.xfiles.ui.components.positioned
import io.github.thomaszimmer13.xfiles.ui.explorer.CompressDialog
import io.github.thomaszimmer13.xfiles.ui.explorer.TrashConfirmDialog
import io.github.thomaszimmer13.xfiles.ui.components.ItemRow
import io.github.thomaszimmer13.xfiles.ui.components.RowAction
import io.github.thomaszimmer13.xfiles.ui.components.SectionHeader
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Vérifie la structure lue par TalkBack des composants partagés :
 * une ligne = un seul élément, avec son texte parlé, son libellé d'appui double et ses actions personnalisées.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComponentsAccessibilityTest {

    @get:Rule val compose = createComposeRule()

    @Test fun itemRowIsOneElementWithActions() {
        compose.setContent {
            ItemRow(
                icon = R.drawable.ic_file,
                title = "Facture.pdf",
                detail = "245 ko",
                spoken = "Facture.pdf, document PDF, 245 kilooctets",
                clickLabel = "ouvrir",
                onClick = {},
                actions = listOf(RowAction("Partager") {}, RowAction("Détails") {}),
            )
        }
        // Le texte visible n'est pas un élément séparé : il est remplacé par la description parlée.
        compose.onAllNodesWithText("245 ko").assertCountEquals(0)
        compose.onNodeWithContentDescription("Facture.pdf, document PDF, 245 kilooctets")
            .assert(SemanticsMatcher("appui double nommé « ouvrir »") { it.config[SemanticsActions.OnClick].label == "ouvrir" })
            .assert(
                SemanticsMatcher("actions Partager et Détails") { node ->
                    node.config[SemanticsActions.CustomActions].map { it.label } == listOf("Partager", "Détails")
                },
            )
    }

    @Test fun headersAreHeadings() {
        compose.setContent {
            SectionHeader("Favoris")
            XFilesTopBar(title = "Documents", spokenTitle = "Documents, 3 éléments")
        }
        val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        compose.onNodeWithText("Favoris").assert(isHeading)
        compose.onNodeWithContentDescription("Documents, 3 éléments").assert(isHeading)
    }

    @Test fun noUnlabelledIcon() {
        compose.setContent { XFilesTopBar(title = "Documents", onBack = {}) }
        compose.onNodeWithContentDescription("Retour").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
    }

    @Test fun positionFollowsSetting() {
        var on = ""
        var off = ""
        compose.setContent {
            on = positioned("Facture.pdf", 2, 12)
            CompositionLocalProvider(LocalAnnouncePosition provides false) { off = positioned("Facture.pdf", 2, 12) }
        }
        compose.waitForIdle()
        assertEquals("Facture.pdf, 3 sur 12", on)
        assertEquals("Facture.pdf", off)
    }

    @Test fun showPasswordIsOneCheckbox() {
        compose.setContent {
            var visible by remember { mutableStateOf(false) }
            ShowPasswordRow(visible) { visible = it }
        }
        val row = compose.onNode(hasText("Afficher le mot de passe") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        row.assertIsOff()
        row.performClick()
        row.assertIsOn()
    }

    @Test fun compressFormatsAreRadioButtons() {
        compose.setContent { CompressDialog(subject = "Projet", suggestedName = "Projet", onConfirm = { _, _, _ -> }, onDismiss = {}) }
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        compose.onAllNodes(radio).assertCountEquals(3)
        compose.onNode(hasText("Zip, lisible partout") and radio).assertIsSelected()
        compose.onNodeWithText("Mot de passe (facultatif)").assertExists()
        compose.onNode(hasText("7z, plus compact") and radio).performClick().assertIsSelected()
        // Pas de mot de passe en 7z : le champ disparaît, une phrase explique pourquoi.
        compose.onAllNodesWithText("Mot de passe (facultatif)").assertCountEquals(0)
        compose.onNodeWithText("Le mot de passe n'est proposé que pour le format zip.").assertExists()
    }

    @Test fun trashCheckboxChangesConfirmButton() {
        compose.setContent { TrashConfirmDialog("vers la corbeille", "définitivement", {}, {}, {}) }
        compose.onNodeWithText("Mettre à la corbeille", useUnmergedTree = true).assertExists()
        compose.onNode(hasText("Supprimer définitivement, sans passer par la corbeille") and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
            .performClick()
        compose.onNodeWithText("Supprimer définitivement").assertExists()
    }
}
