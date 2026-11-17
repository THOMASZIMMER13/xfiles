package io.github.thomaszimmer13.xfiles.ui.document

import android.app.Application
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.data.document.EditableText
import io.github.thomaszimmer13.xfiles.data.document.TextFileCodec
import io.github.thomaszimmer13.xfiles.ui.components.Announcer
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class EditorProblem { NOT_FOUND, TOO_BIG, UNREADABLE }

data class EditorUiState(
    val path: String = "",
    val loading: Boolean = true,
    val content: EditableText? = null,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val problem: EditorProblem? = null,
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as XFilesApplication).container
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()
    val announcer = Announcer(viewModelScope)

    fun load(path: String) {
        if (_state.value.path == path && _state.value.dirty) return
        _state.value = EditorUiState(path = path)
        viewModelScope.launch {
            val file = File(path)
            val content = try {
                withContext(Dispatchers.IO) { TextFileCodec.read(file) }
            } catch (_: TextFileCodec.TooBig) {
                return@launch _state.update { it.copy(loading = false, problem = EditorProblem.TOO_BIG) }
            } catch (_: java.io.FileNotFoundException) {
                return@launch _state.update { it.copy(loading = false, problem = EditorProblem.NOT_FOUND) }
            } catch (_: Exception) {
                return@launch _state.update { it.copy(loading = false, problem = EditorProblem.UNREADABLE) }
            }
            _state.update { it.copy(loading = false, content = content) }
        }
    }

    fun markDirty() {
        if (!_state.value.dirty && _state.value.content != null) _state.update { it.copy(dirty = true) }
    }

    /** Enregistre [text] puis appelle [then] si tout s'est bien passé. */
    fun save(text: String, then: () -> Unit = {}) {
        val s = _state.value
        val content = s.content ?: return
        if (s.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                val changedEncoding = withContext(Dispatchers.IO) {
                    val (bytes, changed) = TextFileCodec.encode(text, content.format)
                    TextFileCodec.writeAtomically(File(s.path), bytes)
                    changed
                }
                val format = if (changedEncoding) content.format.copy(charset = Charsets.UTF_8, bom = false) else content.format
                _state.update { it.copy(saving = false, dirty = false, content = EditableText(text, format)) }
                container.feedback.success()
                announcer.announce(app.getString(if (changedEncoding) R.string.editor_saved_utf8 else R.string.editor_saved))
                then()
            } catch (_: Exception) {
                _state.update { it.copy(saving = false) }
                container.feedback.failure()
                announcer.announce(app.getString(R.string.editor_save_failed))
            }
        }
    }
}

/**
 * Éditeur de texte brut : un vrai champ de saisie Android (EditText), que TalkBack sait parcourir
 * par caractère, mot, ligne et paragraphe, avec sélection, copier, coller.
 * Encodage, marque d'ordre des octets et fins de ligne du fichier sont conservés à l'enregistrement.
 */
@Composable
fun EditorScreen(path: String, onBack: () -> Unit, viewModel: EditorViewModel = viewModel(key = "editor:$path")) {
    LaunchedEffect(path) { viewModel.load(path) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val announcement by viewModel.announcer.message.collectAsStateWithLifecycle()
    val file = remember(path) { File(path) }
    var askLeave by rememberSaveable { mutableStateOf(false) }
    val editor = remember { arrayOfNulls<EditText>(1) }
    val titleFocus = remember { FocusRequester() }
    FocusOnceEffect(ready = !state.loading) { titleFocus.requestFocus() }

    fun leave() {
        if (state.dirty) askLeave = true else onBack()
    }
    BackHandler(enabled = state.dirty) { askLeave = true }

    val title = stringResource(R.string.editor_title, file.name)
    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(
            title = title,
            spokenTitle = if (state.dirty) stringResource(R.string.editor_title_dirty, file.name) else title,
            onBack = ::leave,
            titleFocus = titleFocus,
            actions = {
                if (state.content != null) {
                    TextButton(onClick = { editor[0]?.let { viewModel.save(it.text.toString()) } }, enabled = state.dirty && !state.saving) {
                        Text(stringResource(R.string.editor_save))
                    }
                }
            },
        )
        StatusMessage(announcement)
        val content = state.content
        when {
            state.loading -> CircularProgressIndicator(Modifier.padding(32.dp))
            content != null -> {
                val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
                val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
                val label = stringResource(R.string.editor_field, file.name)
                AndroidView(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    factory = { context ->
                        EditText(context).apply {
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                            isSingleLine = false
                            gravity = Gravity.TOP or Gravity.START
                            setHorizontallyScrolling(false)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                            setTextColor(textColor)
                            setHintTextColor(hintColor)
                            background = null
                            hint = label
                            contentDescription = null
                            setText(content.text)
                            addTextChangedListener(object : TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                                override fun afterTextChanged(s: Editable?) {
                                    viewModel.markDirty()
                                }
                            })
                            editor[0] = this
                        }
                    },
                    update = { view ->
                        view.setTextColor(textColor)
                        view.hint = label
                    },
                )
            }
            else -> Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        when (state.problem) {
                            EditorProblem.NOT_FOUND -> R.string.doc_problem_not_found
                            EditorProblem.TOO_BIG -> R.string.editor_too_big
                            else -> R.string.doc_problem_unreadable
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            }
        }
    }

    if (askLeave) {
        AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text(stringResource(R.string.editor_unsaved_title), modifier = Modifier.semantics { heading() }) },
            text = { Text(stringResource(R.string.editor_unsaved_message, file.name)) },
            confirmButton = {
                TextButton(onClick = {
                    askLeave = false
                    editor[0]?.let { viewModel.save(it.text.toString(), then = onBack) }
                }) { Text(stringResource(R.string.editor_save)) }
            },
            dismissButton = {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    TextButton(onClick = { askLeave = false; onBack() }) { Text(stringResource(R.string.editor_discard)) }
                    TextButton(onClick = { askLeave = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }
}
