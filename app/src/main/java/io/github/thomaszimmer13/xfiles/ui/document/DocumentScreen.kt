package io.github.thomaszimmer13.xfiles.ui.document

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.data.document.DocBlock
import io.github.thomaszimmer13.xfiles.data.document.DocLink
import io.github.thomaszimmer13.xfiles.data.document.Document
import io.github.thomaszimmer13.xfiles.data.document.DocumentReader
import io.github.thomaszimmer13.xfiles.data.document.LinkKind
import io.github.thomaszimmer13.xfiles.data.ocr.OcrModelState
import io.github.thomaszimmer13.xfiles.ui.browser.Routes
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.PasswordDialog
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import io.github.thomaszimmer13.xfiles.ui.components.rememberFileLauncher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import java.io.File

/** Ce qu'une ligne de document dit à TalkBack et ses actions (liens, copier). */
private class BlockA11y(val spoken: String?, val actions: List<Pair<String, () -> Unit>>, val primary: (() -> Unit)?, val primaryLabel: String?)

/**
 * Lecteur de documents : un élément TalkBack par paragraphe, titre ou ligne de tableau.
 * Les titres (et les débuts de page des PDF) sont des en-têtes : navigation par titres de TalkBack.
 * Les liens, numéros, courriels et adresses sont des actions personnalisées du paragraphe
 * (« Appeler 06 12 34 56 78 ») ; appui double = ouvrir le lien quand il n'y en a qu'un.
 */
@OptIn(FlowPreview::class)
@Composable
fun DocumentScreen(
    path: String,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    ocr: Boolean = false,
    viewModel: DocumentViewModel = viewModel(key = "doc:$path:$ocr"),
) {
    LaunchedEffect(path) { viewModel.load(path, ocr = ocr) }
    // En quittant le lecteur, la reconnaissance en cours s'arrête.
    DisposableEffect(viewModel) { onDispose { viewModel.stopOcr(silent = true) } }
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scale by viewModel.textScale.collectAsStateWithLifecycle()
    val announcement by viewModel.announcer.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launcher = rememberFileLauncher(onRoute = onOpen, onProblem = viewModel::announce)
    val file = remember(path) { File(path) }
    val doc = state.document

    var searching by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var linkChoice by remember { mutableStateOf<List<Pair<String, () -> Unit>>?>(null) }

    val listState = rememberLazyListState()
    val titleFocus = remember { FocusRequester() }
    val blockFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    var focusIndex by remember { mutableIntStateOf(-1) }

    // À l'ouverture : focus sur le titre, sauf reprise de lecture (focus sur le bloc mémorisé, plus bas).
    FocusOnceEffect(ready = !state.loading && state.focusBlock == null) { titleFocus.requestFocus() }

    LaunchedEffect(state.focusBlock) {
        val target = state.focusBlock ?: return@LaunchedEffect
        listState.scrollToItem(target)
        focusIndex = target
        withFrameNanos { }
        withFrameNanos { }
        runCatching { blockFocus.requestFocus() }
        viewModel.consumeFocus()
    }

    // Position de lecture : premier bloc visible, mémorisé après une seconde d'immobilité.
    LaunchedEffect(doc != null) {
        if (doc == null) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }.debounce(1_000).collect { viewModel.savePosition(it) }
    }

    val labels = LinkLabels(
        call = stringResource(R.string.link_call),
        write = stringResource(R.string.link_write),
        web = stringResource(R.string.link_web),
        address = stringResource(R.string.link_address),
        internal = stringResource(R.string.link_internal),
        copy = stringResource(R.string.doc_copy_paragraph),
        copied = stringResource(R.string.doc_copied),
        noApp = stringResource(R.string.link_no_app),
        notFound = stringResource(R.string.doc_internal_not_found),
        chooseLink = stringResource(R.string.doc_choose_link),
        headerSep = stringResource(R.string.doc_cell_separator),
    )

    val title = doc?.title?.ifBlank { null } ?: file.name
    val spokenTitle = when {
        doc == null -> title
        doc.pages > 0 -> stringResource(R.string.doc_title_pages, title, doc.pages)
        else -> title
    }

    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(
            title = title,
            spokenTitle = spokenTitle,
            onBack = onBack,
            titleFocus = titleFocus,
            actions = {
                if (doc != null) {
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) viewModel.closeSearch()
                    }) {
                        Icon(
                            painterResource(if (searching) R.drawable.ic_close else R.drawable.ic_search),
                            contentDescription = stringResource(if (searching) R.string.doc_search_close else R.string.doc_search),
                        )
                    }
                    IconButton(onClick = { dialog = DIALOG_TOC }) {
                        Icon(painterResource(R.drawable.ic_toc), contentDescription = stringResource(R.string.doc_toc))
                    }
                }
                IconButton(onClick = { menu = true }) {
                    Icon(painterResource(R.drawable.ic_more), contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (doc != null && doc.pages > 0) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.doc_go_to_page)) }, onClick = { menu = false; dialog = DIALOG_PAGE })
                    }
                    if (doc != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.doc_text_size)) }, onClick = { menu = false; dialog = DIALOG_SCALE })
                    }
                    if (doc != null && DocumentReader.editable(file.name)) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.doc_edit)) }, onClick = { menu = false; onOpen(Routes.editor(path)) })
                    }
                    state.ocr?.takeIf { !it.running && it.done < it.total }?.let {
                        DropdownMenuItem(text = { Text(stringResource(R.string.ocr_restart)) }, onClick = { menu = false; viewModel.startOcr() })
                    }
                    if (doc != null && state.ocr?.running == false) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.ocr_save_text)) }, onClick = { menu = false; viewModel.saveRecognizedText() })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_open_with)) }, onClick = { menu = false; launcher.openWith(path, file.name) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_share)) }, onClick = { menu = false; launcher.share(path, file.name) })
                }
            },
        )

        if (searching && doc != null) {
            SearchBar(
                query = state.query,
                hasMatches = state.matches.isNotEmpty(),
                focus = searchFocus,
                onQuery = viewModel::search,
                onNext = { viewModel.nextMatch(true) },
                onPrevious = { viewModel.nextMatch(false) },
            )
            LaunchedEffect(Unit) { withFrameNanos { }; runCatching { searchFocus.requestFocus() } }
        }

        StatusMessage(announcement)

        (modelState as? OcrModelState.Downloading)?.let { d ->
            ProgressLine(
                label = stringResource(R.string.ocr_downloading, (d.fraction * 100).toInt()),
                fraction = d.fraction,
                action = stringResource(R.string.action_cancel),
                onAction = viewModel::cancelDownload,
            )
        }
        state.ocr?.takeIf { it.running }?.let { run ->
            ProgressLine(
                label = if (run.total == 1) stringResource(R.string.ocr_running_one) else stringResource(R.string.ocr_running, run.done + 1, run.total),
                fraction = if (run.total == 1) null else run.done.toFloat() / run.total,
                action = stringResource(R.string.ocr_stop),
                onAction = { viewModel.stopOcr() },
            )
        }

        when {
            state.loading -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(stringResource(R.string.doc_loading), modifier = Modifier.padding(top = 16.dp))
            }
            doc != null -> {
                val headerOf = remember(doc) { tableHeaders(doc) }
                val textStyle = MaterialTheme.typography.bodyLarge.scaled(scale)
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(doc.blocks) { index, block ->
                        val a11y = remember(block, labels) {
                            blockA11y(context, block, headerOf[index], doc, labels, viewModel::announce, viewModel::jumpTo, { linkChoice = it })
                        }
                        val focusModifier = if (index == focusIndex) Modifier.focusRequester(blockFocus).focusable() else Modifier
                        DocBlockView(block, textStyle, scale, a11y, focusModifier, highlighted = state.currentMatch >= 0 && state.matches.getOrNull(state.currentMatch) == index)
                    }
                }
            }
            state.problem == null -> if (state.ocr?.running != true) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.ocr_needed), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = viewModel::startOcr) { Text(stringResource(R.string.ocr_start)) }
                    OutlinedButton(onClick = { launcher.openWith(path, file.name) }) { Text(stringResource(R.string.action_open_with)) }
                }
            }
            else -> ProblemView(
                problem = state.problem,
                onOpenWith = { launcher.openWith(path, file.name) },
                onRecognize = viewModel::startOcr,
                onBack = onBack,
            )
        }
    }

    if (state.askModels) {
        AlertDialog(
            onDismissRequest = viewModel::declineModels,
            title = { Text(stringResource(R.string.ocr_models_title), modifier = Modifier.semantics { heading() }) },
            text = { Text(stringResource(R.string.ocr_models_message, android.text.format.Formatter.formatShortFileSize(context, viewModel.modelSize))) },
            confirmButton = { TextButton(onClick = viewModel::acceptModels) { Text(stringResource(R.string.ocr_models_download)) } },
            dismissButton = { TextButton(onClick = viewModel::declineModels) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (state.problem == DocProblem.PASSWORD || state.problem == DocProblem.WRONG_PASSWORD) {
        PasswordDialog(
            title = stringResource(R.string.doc_password_title),
            message = stringResource(R.string.doc_password_message, file.name),
            confirmLabel = stringResource(R.string.doc_password_open),
            error = if (state.problem == DocProblem.WRONG_PASSWORD) stringResource(R.string.password_wrong) else null,
            onConfirm = { viewModel.load(path, password = it, force = true) },
            onDismiss = onBack,
        )
    }

    linkChoice?.let { choices ->
        AlertDialog(
            onDismissRequest = { linkChoice = null },
            title = { Text(labels.chooseLink, modifier = Modifier.semantics { heading() }) },
            text = {
                Column {
                    choices.forEach { (label, run) ->
                        TextButton(onClick = { linkChoice = null; run() }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { linkChoice = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (doc != null) {
        when (dialog) {
            DIALOG_TOC -> TocDialog(doc, onPick = { dialog = null; viewModel.jumpTo(it) }, onDismiss = { dialog = null })
            DIALOG_PAGE -> PageDialog(doc.pages, onPick = { page ->
                dialog = null
                val index = doc.blocks.indexOfFirst { it is DocBlock.PageStart && it.number == page }
                if (index >= 0) viewModel.jumpTo(index)
            }, onDismiss = { dialog = null })
            DIALOG_SCALE -> ScaleDialog(scale, onPick = { dialog = null; viewModel.setTextScale(it) }, onDismiss = { dialog = null })
        }
    }
}

private const val DIALOG_TOC = "toc"
private const val DIALOG_PAGE = "page"
private const val DIALOG_SCALE = "scale"

private val SCALES = listOf(1f, 1.3f, 1.6f)

private fun TextStyle.scaled(scale: Float) = if (scale == 1f) this else copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)

private class LinkLabels(
    val call: String,
    val write: String,
    val web: String,
    val address: String,
    val internal: String,
    val copy: String,
    val copied: String,
    val noApp: String,
    val notFound: String,
    val chooseLink: String,
    val headerSep: String,
)

/** Pour chaque ligne de tableau, la ligne d'en-tête du même tableau (lignes consécutives), si elle existe. */
private fun tableHeaders(doc: Document): Map<Int, DocBlock.TableRow> {
    val result = HashMap<Int, DocBlock.TableRow>()
    var header: DocBlock.TableRow? = null
    doc.blocks.forEachIndexed { i, b ->
        if (b is DocBlock.TableRow) {
            if (b.header) header = b else header?.let { result[i] = it }
        } else {
            header = null
        }
    }
    return result
}

private fun linksOf(block: DocBlock): List<DocLink> = when (block) {
    is DocBlock.Paragraph -> block.links
    is DocBlock.Heading -> block.links
    is DocBlock.ListItem -> block.links
    else -> emptyList()
}

private fun blockA11y(
    context: Context,
    block: DocBlock,
    header: DocBlock.TableRow?,
    doc: Document,
    labels: LinkLabels,
    announce: (String) -> Unit,
    jumpTo: (Int) -> Unit,
    choose: (List<Pair<String, () -> Unit>>) -> Unit,
): BlockA11y {
    val spoken = when (block) {
        is DocBlock.TableRow -> if (header != null) {
            block.cells.mapIndexed { i, cell ->
                val h = header.cells.getOrNull(i)?.takeIf { it.isNotBlank() }
                if (h != null && cell.isNotBlank()) "$h${labels.headerSep}$cell" else cell
            }.filter { it.isNotBlank() }.joinToString(", ")
        } else {
            block.cells.filter { it.isNotBlank() }.joinToString(", ")
        }
        is DocBlock.PageStart -> context.getString(R.string.doc_page_n, block.number, doc.pages).replaceFirstChar { it.uppercase() }
        else -> null
    }
    val linkActions = linksOf(block).map { link ->
        val shown = block.text.substring(link.start.coerceIn(0, block.text.length), link.end.coerceIn(0, block.text.length))
        val label = when (link.kind) {
            LinkKind.PHONE -> labels.call.format(shown)
            LinkKind.EMAIL -> labels.write.format(shown)
            LinkKind.ADDRESS -> labels.address.format(shown)
            LinkKind.INTERNAL -> labels.internal.format(shown)
            LinkKind.WEB -> labels.web.format(shown)
        }
        label to { openLink(context, link, shown, doc, labels, announce, jumpTo) }
    }
    val copy: Pair<String, () -> Unit>? = if (block.text.isNotBlank()) {
        labels.copy to {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText(doc.title, spoken ?: block.text))
            announce(labels.copied)
        }
    } else {
        null
    }
    val primary: (() -> Unit)? = when (linkActions.size) {
        0 -> null
        1 -> linkActions[0].second
        else -> ({ choose(linkActions) })
    }
    val primaryLabel = when (linkActions.size) {
        0 -> null
        1 -> linkActions[0].first.replaceFirstChar { it.lowercase() }
        else -> labels.chooseLink.replaceFirstChar { it.lowercase() }
    }
    return BlockA11y(spoken, linkActions + listOfNotNull(copy), primary, primaryLabel)
}

private fun openLink(
    context: Context,
    link: DocLink,
    shown: String,
    doc: Document,
    labels: LinkLabels,
    announce: (String) -> Unit,
    jumpTo: (Int) -> Unit,
) {
    if (link.kind == LinkKind.INTERNAL) {
        val wanted = shown.trim()
        val index = doc.blocks.indexOfFirst { it is DocBlock.Heading && it.text.trim().equals(wanted, ignoreCase = true) }
        if (index >= 0) jumpTo(index) else announce(labels.notFound)
        return
    }
    val uri = link.target.toUri()
    val intent = when (link.kind) {
        LinkKind.PHONE -> Intent(Intent.ACTION_DIAL, uri)
        LinkKind.EMAIL -> Intent(Intent.ACTION_SENDTO, uri)
        else -> Intent(Intent.ACTION_VIEW, uri)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        announce(labels.noApp)
    } catch (_: SecurityException) {
        announce(labels.noApp)
    }
}

private fun annotated(text: String, links: List<DocLink>, linkColor: androidx.compose.ui.graphics.Color): AnnotatedString =
    if (links.isEmpty()) {
        AnnotatedString(text)
    } else {
        buildAnnotatedString {
            append(text)
            links.forEach { l ->
                val start = l.start.coerceIn(0, text.length)
                val end = l.end.coerceIn(start, text.length)
                if (end > start) addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), start, end)
            }
        }
    }

@Composable
private fun DocBlockView(
    block: DocBlock,
    textStyle: TextStyle,
    scale: Float,
    a11y: BlockA11y,
    focusModifier: Modifier,
    highlighted: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val base = Modifier
        .fillMaxWidth()
        .then(focusModifier)
        .then(if (a11y.primary != null) Modifier.clickable(onClick = a11y.primary) else Modifier)
    val semantics = Modifier.semantics {
        if (a11y.actions.isNotEmpty()) customActions = a11y.actions.map { (label, run) -> CustomAccessibilityAction(label) { run(); true } }
        if (a11y.primary != null) onClick(label = a11y.primaryLabel) { a11y.primary.invoke(); true }
    }
    val links = linksOf(block)
    when (block) {
        is DocBlock.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            }.scaled(scale)
            Text(
                annotated(block.text, links, colors.primary),
                style = style.copy(fontWeight = FontWeight.Bold),
                color = if (highlighted) colors.primary else colors.onSurface,
                modifier = base.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp).then(semantics).semantics { heading() },
            )
        }
        is DocBlock.PageStart -> Column(base) {
            HorizontalDivider()
            Text(
                a11y.spoken.orEmpty(),
                style = MaterialTheme.typography.labelLarge.scaled(scale),
                color = colors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
            )
        }
        is DocBlock.TableRow -> Row(
            base.heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 4.dp).clearAndSetSemantics {
                contentDescription = a11y.spoken.orEmpty()
                if (a11y.actions.isNotEmpty()) customActions = a11y.actions.map { (label, run) -> CustomAccessibilityAction(label) { run(); true } }
            },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            block.cells.forEach { cell ->
                Text(
                    cell,
                    style = textStyle.copy(fontWeight = if (block.header) FontWeight.Bold else null),
                    color = if (highlighted) colors.primary else colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        is DocBlock.ListItem -> Text(
            annotated("${block.marker} ${block.text}", links.map { it.copy(start = it.start + block.marker.length + 1, end = it.end + block.marker.length + 1) }, colors.primary),
            style = textStyle,
            color = if (highlighted) colors.primary else colors.onSurface,
            modifier = base.padding(start = (16 + 20 * block.level).dp, end = 16.dp, top = 4.dp, bottom = 4.dp).then(semantics),
        )
        is DocBlock.Preformatted -> Text(
            block.text,
            style = textStyle.copy(fontFamily = FontFamily.Monospace),
            color = if (highlighted) colors.primary else colors.onSurface,
            modifier = base.padding(horizontal = 16.dp, vertical = 2.dp).then(semantics),
        )
        is DocBlock.Paragraph -> Text(
            annotated(block.text, links, colors.primary),
            style = textStyle,
            color = if (highlighted) colors.primary else colors.onSurface,
            modifier = base.padding(horizontal = 16.dp, vertical = 6.dp).then(semantics),
        )
    }
}

@Composable
private fun SearchBar(
    query: String,
    hasMatches: Boolean,
    focus: FocusRequester,
    onQuery: (String) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text(stringResource(R.string.doc_search_field)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onNext() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = onPrevious, enabled = hasMatches) { Text(stringResource(R.string.doc_search_previous)) }
            OutlinedButton(onClick = onNext, enabled = hasMatches) { Text(stringResource(R.string.doc_search_next)) }
        }
    }
}

@Composable
private fun ProblemView(problem: DocProblem?, onOpenWith: () -> Unit, onRecognize: () -> Unit, onBack: () -> Unit) {
    val message = when (problem) {
        DocProblem.NOT_FOUND -> stringResource(R.string.doc_problem_not_found)
        DocProblem.PDF_IMAGES -> stringResource(R.string.doc_problem_pdf_images)
        DocProblem.PASSWORD, DocProblem.WRONG_PASSWORD -> stringResource(R.string.doc_problem_password)
        else -> stringResource(R.string.doc_problem_unreadable)
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        if (problem == DocProblem.PDF_IMAGES) Button(onClick = onRecognize) { Text(stringResource(R.string.ocr_start)) }
        if (problem != DocProblem.NOT_FOUND) OutlinedButton(onClick = onOpenWith) { Text(stringResource(R.string.action_open_with)) }
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
    }
}

/** Barre de progression lue comme un seul élément : « Page 3 sur 12 », avec son bouton à côté. */
@Composable
private fun ProgressLine(label: String, fraction: Float?, action: String, onAction: () -> Unit) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(
            Modifier.weight(1f).semantics(mergeDescendants = true) {
                contentDescription = label
                if (fraction != null) progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            },
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun TocDialog(doc: Document, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val entries = remember(doc) {
        doc.blocks.withIndex().filter { it.value is DocBlock.Heading }.map { it.index to (it.value as DocBlock.Heading) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.doc_toc), modifier = Modifier.semantics { heading() }) },
        text = {
            if (entries.isEmpty()) {
                Text(stringResource(R.string.doc_toc_empty))
            } else {
                LazyColumn {
                    itemsIndexed(entries) { i, (index, heading) ->
                        val spoken = stringResource(R.string.doc_toc_entry, heading.text, heading.level, i + 1, entries.size)
                        Text(
                            heading.text,
                            style = if (heading.level <= 1) MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { onPick(index) }
                                .padding(start = (12 * (heading.level - 1).coerceAtLeast(0)).dp, top = 12.dp, bottom = 12.dp)
                                .clearAndSetSemantics {
                                    contentDescription = spoken
                                    onClick(label = null) { onPick(index); true }
                                },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun PageDialog(pages: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val page = value.toIntOrNull()?.takeIf { it in 1..pages }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.doc_go_to_page), modifier = Modifier.semantics { heading() }) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { v -> value = v.filter(Char::isDigit).take(6) },
                label = { Text(stringResource(R.string.doc_page_field, pages)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { page?.let(onPick) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { page?.let(onPick) }, enabled = page != null) { Text(stringResource(R.string.doc_go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ScaleDialog(current: Float, onPick: (Float) -> Unit, onDismiss: () -> Unit) {
    val names = listOf(stringResource(R.string.doc_size_normal), stringResource(R.string.doc_size_large), stringResource(R.string.doc_size_huge))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.doc_text_size), modifier = Modifier.semantics { heading() }) },
        text = {
            Column {
                SCALES.forEachIndexed { i, s ->
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = s == current, role = Role.RadioButton) { onPick(s) },
                    ) {
                        RadioButton(selected = s == current, onClick = null)
                        Text(names[i], style = MaterialTheme.typography.bodyLarge.scaled(s), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
