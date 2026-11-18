package io.github.thomaszimmer13.xfiles.ui.rename

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.data.rename.CaseChange
import io.github.thomaszimmer13.xfiles.data.rename.RenameMode
import io.github.thomaszimmer13.xfiles.data.rename.RenamePlanItem
import io.github.thomaszimmer13.xfiles.data.rename.RenameStatus
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar

/**
 * Renommage par lot : réglages en haut, puis l'aperçu (un élément par fichier : « IMG_001.jpg
 * devient Vacances 01.jpg »). Le résumé est annoncé quand on arrête de taper. Après le renommage,
 * « Annuler le renommage » remet les anciens noms.
 */
@Composable
fun BatchRenameScreen(generation: Int, onBack: () -> Unit, viewModel: BatchRenameViewModel = viewModel(key = "rename:$generation")) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val announcement by viewModel.announcer.message.collectAsStateWithLifecycle()
    val rules = state.rules
    val titleFocus = remember { FocusRequester() }
    FocusOnceEffect(ready = true) { titleFocus.requestFocus() }
    val count = state.sources.size
    val renamed = state.plan.count { it.status == RenameStatus.RENAMED }
    val blocked = state.plan.any { it.status != RenameStatus.RENAMED && it.status != RenameStatus.UNCHANGED }

    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(
            title = pluralStringResource(R.plurals.rename_title, count, count),
            onBack = onBack,
            titleFocus = titleFocus,
        )
        StatusMessage(announcement)
        val done = state.done
        if (done != null) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(pluralStringResource(R.plurals.rename_done, done.size, done.size), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onBack) { Text(stringResource(R.string.rename_finished)) }
                OutlinedButton(onClick = viewModel::undo, enabled = !state.working) { Text(stringResource(R.string.rename_undo)) }
            }
            return@Column
        }
        LazyColumn(Modifier.weight(1f)) {
            item {
                Column(Modifier.selectableGroup()) {
                    RadioLine(stringResource(R.string.rename_mode_replace), rules.mode == RenameMode.REPLACE) { viewModel.update(rules.copy(mode = RenameMode.REPLACE)) }
                    RadioLine(stringResource(R.string.rename_mode_template), rules.mode == RenameMode.TEMPLATE) { viewModel.update(rules.copy(mode = RenameMode.TEMPLATE)) }
                }
            }
            if (rules.mode == RenameMode.REPLACE) {
                item { Field(stringResource(R.string.rename_find), rules.find) { viewModel.update(rules.copy(find = it)) } }
                item { Field(stringResource(R.string.rename_replace), rules.replace) { viewModel.update(rules.copy(replace = it)) } }
                item { SwitchLine(stringResource(R.string.rename_regex), rules.regex) { viewModel.update(rules.copy(regex = it)) } }
                item { SwitchLine(stringResource(R.string.rename_ignore_case), rules.ignoreCase) { viewModel.update(rules.copy(ignoreCase = it)) } }
            } else {
                item { Field(stringResource(R.string.rename_template), rules.template, stringResource(R.string.rename_template_help)) { viewModel.update(rules.copy(template = it)) } }
                item {
                    Field(stringResource(R.string.rename_start), rules.start.toString(), keyboard = KeyboardType.Number) { v ->
                        v.filter(Char::isDigit).take(6).toIntOrNull()?.let { viewModel.update(rules.copy(start = it)) }
                    }
                }
                item {
                    Field(stringResource(R.string.rename_digits), if (rules.digits == 0) "" else rules.digits.toString(), stringResource(R.string.rename_digits_help), KeyboardType.Number) { v ->
                        viewModel.update(rules.copy(digits = v.filter(Char::isDigit).take(1).toIntOrNull() ?: 0))
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.rename_case),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp).semantics { heading() },
                )
            }
            item {
                Column(Modifier.selectableGroup()) {
                    listOf(
                        CaseChange.NONE to R.string.rename_case_none,
                        CaseChange.LOWER to R.string.rename_case_lower,
                        CaseChange.UPPER to R.string.rename_case_upper,
                        CaseChange.WORDS to R.string.rename_case_words,
                        CaseChange.SENTENCE to R.string.rename_case_sentence,
                    ).forEach { (c, label) -> RadioLine(stringResource(label), rules.case == c) { viewModel.update(rules.copy(case = c)) } }
                }
            }
            item { SwitchLine(stringResource(R.string.rename_include_extension), rules.includeExtension) { viewModel.update(rules.copy(includeExtension = it)) } }
            item {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                Text(
                    stringResource(R.string.rename_preview) + " : " + viewModel.summary(state.plan),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp).semantics { heading() },
                )
            }
            items(state.plan, key = { it.source.path }) { PreviewLine(it) }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            Button(onClick = viewModel::apply, enabled = renamed > 0 && !blocked && !state.working, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(
                    when {
                        blocked -> stringResource(R.string.rename_fix_conflicts)
                        else -> pluralStringResource(R.plurals.rename_apply, renamed, renamed)
                    },
                )
            }
        }
    }
}

@Composable
private fun PreviewLine(item: RenamePlanItem) {
    val text = when (item.status) {
        RenameStatus.RENAMED -> stringResource(R.string.rename_line_renamed, item.source.name, item.newName)
        RenameStatus.UNCHANGED -> stringResource(R.string.rename_line_unchanged, item.source.name)
        RenameStatus.DUPLICATE -> stringResource(R.string.rename_line_duplicate, item.source.name, item.newName)
        RenameStatus.EXISTS -> stringResource(R.string.rename_line_exists, item.source.name, item.newName)
        RenameStatus.INVALID -> stringResource(R.string.rename_line_invalid, item.source.name, item.newName)
        RenameStatus.BAD_PATTERN -> stringResource(R.string.rename_line_unchanged, item.source.name)
    }
    val problem = item.status != RenameStatus.RENAMED && item.status != RenameStatus.UNCHANGED
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp).clearAndSetSemantics { contentDescription = text },
    )
}

@Composable
private fun Field(label: String, value: String, help: String? = null, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = help?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun RadioLine(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(horizontal = 16.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(horizontal = 16.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
