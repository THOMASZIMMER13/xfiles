package io.github.thomaszimmer13.xfiles.ui.explorer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.a11y.FileDescriptions
import io.github.thomaszimmer13.xfiles.a11y.SizeFormatter
import io.github.thomaszimmer13.xfiles.data.media.Categories
import io.github.thomaszimmer13.xfiles.data.media.Category
import io.github.thomaszimmer13.xfiles.data.volumes.LocationKind
import io.github.thomaszimmer13.xfiles.ui.components.IncomingBanner
import io.github.thomaszimmer13.xfiles.ui.storage.categoryLabel
import io.github.thomaszimmer13.xfiles.data.volumes.StorageLocation
import io.github.thomaszimmer13.xfiles.ui.browser.Routes
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.SectionHeader
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar

/**
 * Racine de l'onglet Explorer, façon « Parcourir » d'iOS :
 * « Sur l'appareil » (mémoire interne, carte SD, clé USB) puis « Favoris » (dossiers épinglés).
 *
 * @param focusPath chemin d'où l'on revient : le focus est replacé sur la ligne correspondante.
 */
@Composable
fun ExplorerRootScreen(
    onOpen: (String) -> Unit,
    focusPath: String?,
    viewModel: ExplorerRootViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val announcement by viewModel.announcer.message.collectAsStateWithLifecycle()

    // L'espace libre et l'existence des favoris ont pu changer pendant que l'app était en arrière-plan.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    // Clés des lignes dans l'ordre de la liste, pour retrouver la ligne à reprendre au retour.
    val downloads = remember { Categories.downloadsDir().path }
    val keys = buildList {
        add("header-device")
        state.locations.forEach { add("loc:" + it.path) }
        add("trash")
        if (state.shizukuShown) add("shizuku")
        add("header-servers")
        state.servers.forEach { add("srv:" + it.id) }
        add("add-cloud")
        add("add-server")
        add("header-vaults")
        state.vaults.forEach { add("vault:" + it.id) }
        add("add-vault")
        if (state.favorites.isNotEmpty()) {
            add("header-favorites")
            state.favorites.forEach { add("fav:" + it.path) }
        }
        add("header-categories")
        add("cat:downloads")
        Category.entries.forEach { add("cat:" + it.name) }
        add("header-tools")
        add("storage")
    }
    val listState = rememberLazyListState()
    val rowFocus = remember { FocusRequester() }
    var removing by remember { mutableStateOf<io.github.thomaszimmer13.xfiles.data.remote.RemoteServer?>(null) }
    removing?.let { vault ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.vault_remove_title), modifier = Modifier.semantics { heading() }) },
            text = { Text(stringResource(R.string.vault_remove_message, vault.name)) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { removing = null; viewModel.removeVault(vault) }) { Text(stringResource(R.string.vault_remove)) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    // Un favori exact (ex. Documents) a priorité sur le volume qui le contient.
    val focusKey: String? = focusPath?.let { target ->
        when {
            target == TRASH_ROUTE -> "trash"
            target == Routes.SHIZUKU -> "shizuku"
            target == Routes.VAULT_ADD -> "add-vault"
            Routes.parse(target) is Routes.Route.Remote -> (Routes.parse(target) as Routes.Route.Remote).serverId.let { id ->
                when {
                    id == io.github.thomaszimmer13.xfiles.data.shizuku.ShizukuAccess.SERVER_ID -> "shizuku"
                    state.vaults.any { it.id == id } -> "vault:$id"
                    else -> "srv:$id"
                }
            }
            Routes.parse(target) is Routes.Route.ServerEdit -> (Routes.parse(target) as Routes.Route.ServerEdit).let { r ->
                r.serverId?.let { "srv:$it" } ?: if (r.cloud) "add-cloud" else "add-server"
            }
            target == Routes.STORAGE -> "storage"
            target == downloads -> "cat:downloads"
            else -> Category.entries.firstOrNull { Routes.category(it) == target }?.let { "cat:" + it.name }
                ?: state.favorites.firstOrNull { it.path == target }?.let { "fav:" + it.path }
                ?: state.locations.firstOrNull { it.path == target }?.let { "loc:" + it.path }
                ?: state.locations.filter { target.startsWith("${it.path}/") }.maxByOrNull { it.path.length }?.let { "loc:" + it.path }
        }
    }

    FocusOnceEffect(ready = focusPath != null && state.locations.isNotEmpty()) {
        val index = keys.indexOf(focusKey ?: return@FocusOnceEffect).takeIf { it >= 0 } ?: return@FocusOnceEffect
        listState.scrollToItem(index)
        withFrameNanos { }
        rowFocus.requestFocus()
    }
    fun focusFor(key: String) = if (key == focusKey) rowFocus else null

    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(
            title = stringResource(R.string.tab_explorer),
            actions = {
                IconButton(onClick = { onOpen(Routes.search(null)) }) {
                    Icon(painterResource(R.drawable.ic_search), contentDescription = stringResource(R.string.search_everywhere))
                }
                IconButton(onClick = { onOpen(Routes.SETTINGS) }) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings_title))
                }
            },
        )
        IncomingBanner(onSaveHere = null)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            item(key = "header-device") {
                SectionHeader(stringResource(R.string.section_on_device))
            }
            items(state.locations, key = { "loc:" + it.path }) { location ->
                LocationRow(location = location, focusRequester = focusFor("loc:" + location.path), onOpen = onOpen)
            }
            item(key = "trash") {
                TrashRow(count = state.trashCount, focusRequester = focusFor("trash"), onOpen = { onOpen(TRASH_ROUTE) })
            }
            if (state.shizukuShown) {
                item(key = "shizuku") {
                    SimpleRow(
                        icon = R.drawable.ic_settings,
                        title = stringResource(R.string.shizuku_title),
                        detail = stringResource(if (state.shizukuReady) R.string.shizuku_row_ready else R.string.shizuku_row_todo),
                        focusRequester = focusFor("shizuku"),
                        onClick = {
                            onOpen(
                                if (state.shizukuReady) Routes.remote(io.github.thomaszimmer13.xfiles.data.shizuku.ShizukuAccess.SERVER_ID)
                                else Routes.SHIZUKU,
                            )
                        },
                    )
                }
            }
            item(key = "header-servers") {
                SectionHeader(stringResource(R.string.section_servers))
            }
            items(state.servers, key = { "srv:" + it.id }) { server ->
                ServerRow(server, focusFor("srv:" + server.id), onOpen)
            }
            item(key = "add-cloud") {
                SimpleRow(
                    icon = R.drawable.ic_cloud,
                    title = stringResource(R.string.cloud_add),
                    detail = stringResource(R.string.cloud_add_detail),
                    focusRequester = focusFor("add-cloud"),
                    onClick = { onOpen(io.github.thomaszimmer13.xfiles.ui.browser.Routes.cloudAdd()) },
                )
            }
            item(key = "add-server") {
                SimpleRow(
                    icon = R.drawable.ic_add,
                    title = stringResource(R.string.server_add),
                    detail = stringResource(R.string.server_add_detail),
                    focusRequester = focusFor("add-server"),
                    onClick = { onOpen(io.github.thomaszimmer13.xfiles.ui.browser.Routes.serverEdit(null)) },
                )
            }
            item(key = "header-vaults") {
                SectionHeader(stringResource(R.string.section_vaults))
            }
            items(state.vaults, key = { "vault:" + it.id }) { vault ->
                VaultRow(
                    vault = vault,
                    open = vault.id in state.openVaults,
                    cloudName = state.servers.firstOrNull { it.id == vault.username }?.name,
                    focusRequester = focusFor("vault:" + vault.id),
                    onOpen = onOpen,
                    onLock = { viewModel.lockVault(vault) },
                    onForgetBio = { viewModel.forgetVaultBio(vault) },
                    onRemove = { removing = vault },
                )
            }
            item(key = "add-vault") {
                SimpleRow(
                    icon = R.drawable.ic_lock,
                    title = stringResource(R.string.vault_new),
                    detail = stringResource(R.string.vault_new_detail),
                    focusRequester = focusFor("add-vault"),
                    onClick = { onOpen(Routes.VAULT_ADD) },
                )
            }
            if (state.favorites.isNotEmpty()) {
                item(key = "header-favorites") {
                    SectionHeader(stringResource(R.string.section_favorites))
                }
                items(state.favorites, key = { "fav:" + it.path }) { favorite ->
                    FavoriteRow(
                        favorite = favorite,
                        focusRequester = focusFor("fav:" + favorite.path),
                        onOpen = onOpen,
                        onRemove = viewModel::removeFavorite,
                    )
                }
            }
            item(key = "header-categories") {
                SectionHeader(stringResource(R.string.section_categories))
            }
            item(key = "cat:downloads") {
                SimpleRow(
                    icon = R.drawable.ic_tab_explorer,
                    title = stringResource(R.string.category_downloads),
                    detail = null,
                    focusRequester = focusFor("cat:downloads"),
                    onClick = { onOpen(downloads) },
                )
            }
            items(Category.entries, key = { "cat:" + it.name }) { category ->
                val count = state.categoryCounts[category]
                SimpleRow(
                    icon = categoryIcon(category),
                    title = stringResource(categoryLabel(category)),
                    detail = count?.let(FileDescriptions::itemCount),
                    focusRequester = focusFor("cat:" + category.name),
                    onClick = { onOpen(Routes.category(category)) },
                )
            }
            item(key = "header-tools") {
                SectionHeader(stringResource(R.string.section_tools))
            }
            item(key = "storage") {
                SimpleRow(
                    icon = R.drawable.ic_delete,
                    title = stringResource(R.string.storage_title),
                    detail = stringResource(R.string.storage_row_detail),
                    focusRequester = focusFor("storage"),
                    onClick = { onOpen(Routes.STORAGE) },
                )
            }
        }
        // Hors de la liste pour être toujours présent, même si la liste défile.
        StatusMessage(announcement)
    }
}

@Composable
private fun LocationRow(location: StorageLocation, focusRequester: FocusRequester?, onOpen: (String) -> Unit) {
    val free = location.freeBytes
    val total = location.totalBytes
    val hasSpace = free != null && total != null
    val visibleDetail = if (hasSpace) {
        stringResource(R.string.location_space, SizeFormatter.short(free!!), SizeFormatter.short(total!!))
    } else {
        null
    }
    val spoken = if (hasSpace) {
        stringResource(R.string.location_spoken_with_space, location.name, SizeFormatter.spoken(free!!), SizeFormatter.spoken(total!!))
    } else {
        location.name
    }
    val openLabel = stringResource(R.string.action_open)

    RootRow(
        icon = if (location.kind == LocationKind.INTERNAL) R.drawable.ic_location_internal else R.drawable.ic_location_removable,
        title = location.name,
        detail = visibleDetail,
        focusRequester = focusRequester,
        onClick = { onOpen(location.path) },
        semantics = {
            contentDescription = spoken
            onClick(label = openLabel) { onOpen(location.path); true }
        },
    )
}

/** « Corbeille, 3 éléments » ; le nombre n'est pas lu tant qu'il n'est pas connu. */
@Composable
private fun TrashRow(count: Int?, focusRequester: FocusRequester?, onOpen: () -> Unit) {
    val title = stringResource(R.string.trash_title)
    val detail = count?.let(FileDescriptions::itemCount)
    val openLabel = stringResource(R.string.action_open)
    RootRow(
        icon = R.drawable.ic_delete,
        title = title,
        detail = detail,
        focusRequester = focusRequester,
        onClick = onOpen,
        semantics = {
            contentDescription = if (detail == null) title else "$title, $detail"
            onClick(label = openLabel) { onOpen(); true }
        },
    )
}

@Composable
private fun FavoriteRow(
    favorite: FavoriteFolder,
    focusRequester: FocusRequester?,
    onOpen: (String) -> Unit,
    onRemove: (FavoriteFolder) -> Unit,
) {
    val where = favorite.parents.joinToString(", ")
    val missing = stringResource(R.string.favorite_missing)
    val spoken = buildString {
        append(favorite.name)
        if (where.isNotEmpty()) append(", ").append(stringResource(R.string.favorite_in, where))
        if (!favorite.exists) append(", ").append(missing)
    }
    val openLabel = stringResource(R.string.action_open)
    val removeLabel = stringResource(R.string.action_remove_favorite)

    RootRow(
        icon = R.drawable.ic_favorite,
        title = favorite.name,
        detail = if (favorite.exists) favorite.parents.joinToString(" › ") else missing,
        focusRequester = focusRequester,
        onClick = { if (favorite.exists) onOpen(favorite.path) },
        semantics = {
            contentDescription = spoken
            if (favorite.exists) onClick(label = openLabel) { onOpen(favorite.path); true }
            customActions = listOf(CustomAccessibilityAction(removeLabel) { onRemove(favorite); true })
        },
    )
}

/** Serveur : « NAS, SMB, 192.168.1.10/Public » ; action « Modifier le serveur ». */
@Composable
private fun ServerRow(server: io.github.thomaszimmer13.xfiles.data.remote.RemoteServer, focusRequester: FocusRequester?, onOpen: (String) -> Unit) {
    val openLabel = stringResource(R.string.action_open)
    val editLabel = stringResource(R.string.server_edit)
    val protocol = stringResource(
        when (server.protocol) {
            io.github.thomaszimmer13.xfiles.data.remote.Protocol.SMB -> R.string.protocol_smb_short
            io.github.thomaszimmer13.xfiles.data.remote.Protocol.SFTP -> R.string.protocol_sftp_short
            io.github.thomaszimmer13.xfiles.data.remote.Protocol.FTP -> R.string.protocol_ftp_short
            io.github.thomaszimmer13.xfiles.data.remote.Protocol.FTPS -> R.string.protocol_ftps_short
            io.github.thomaszimmer13.xfiles.data.remote.Protocol.WEBDAV -> R.string.protocol_webdav_short
            io.github.thomaszimmer13.xfiles.data.remote.Protocol.CLOUD ->
                io.github.thomaszimmer13.xfiles.data.remote.CloudProvider.of(server.host)?.label ?: R.string.cloud_add
            io.github.thomaszimmer13.xfiles.data.remote.Protocol.SHIZUKU -> R.string.shizuku_title
            io.github.thomaszimmer13.xfiles.data.remote.Protocol.VAULT -> R.string.vault_kind
        },
    )
    val detail = if (server.protocol.cloud) {
        if (server.name == protocol) stringResource(R.string.cloud_kind) else protocol
    } else {
        "$protocol, ${server.host}" + server.basePath.takeIf { it.isNotEmpty() }?.let { " " + it.trim('/') }.orEmpty()
    }
    val open = { onOpen(io.github.thomaszimmer13.xfiles.ui.browser.Routes.remote(server.id)) }
    RootRow(
        icon = if (server.protocol.cloud) R.drawable.ic_cloud else R.drawable.ic_server,
        title = server.name,
        detail = detail,
        focusRequester = focusRequester,
        onClick = open,
        semantics = {
            contentDescription = "${server.name}, $detail"
            onClick(label = openLabel) { open(); true }
            customActions = listOf(CustomAccessibilityAction(editLabel) { onOpen(io.github.thomaszimmer13.xfiles.ui.browser.Routes.serverEdit(server.id)); true })
        },
    )
}

/**
 * Coffre : « Perso, coffre verrouillé, sur le téléphone » ; actions Verrouiller (s'il est ouvert),
 * Ne plus utiliser l'empreinte, Retirer le coffre.
 */
@Composable
private fun VaultRow(
    vault: io.github.thomaszimmer13.xfiles.data.remote.RemoteServer,
    open: Boolean,
    cloudName: String?,
    focusRequester: FocusRequester?,
    onOpen: (String) -> Unit,
    onLock: () -> Unit,
    onForgetBio: () -> Unit,
    onRemove: () -> Unit,
) {
    val openLabel = stringResource(R.string.action_open)
    val lockLabel = stringResource(R.string.vault_lock)
    val bioLabel = stringResource(R.string.vault_bio_forget)
    val removeLabel = stringResource(R.string.vault_remove)
    val status = stringResource(if (open) R.string.vault_open_state else R.string.vault_locked_state)
    val where = if (cloudName != null) stringResource(R.string.vault_in_cloud, cloudName) else stringResource(R.string.vault_on_phone)
    val detail = "$status, $where"
    val go = { onOpen(Routes.remote(vault.id)) }
    RootRow(
        icon = R.drawable.ic_lock,
        title = vault.name,
        detail = detail,
        focusRequester = focusRequester,
        onClick = go,
        semantics = {
            contentDescription = "${vault.name}, $detail"
            onClick(label = openLabel) { go(); true }
            customActions = buildList {
                if (open) add(CustomAccessibilityAction(lockLabel) { onLock(); true })
                if (vault.password.isNotEmpty()) add(CustomAccessibilityAction(bioLabel) { onForgetBio(); true })
                add(CustomAccessibilityAction(removeLabel) { onRemove(); true })
            }
        },
    )
}

/** Ligne simple : « Images, 1 234 éléments » ; appui double = ouvrir. */
@Composable
private fun SimpleRow(icon: Int, title: String, detail: String?, focusRequester: FocusRequester?, onClick: () -> Unit) {
    val openLabel = stringResource(R.string.action_open)
    RootRow(
        icon = icon,
        title = title,
        detail = detail,
        focusRequester = focusRequester,
        onClick = onClick,
        semantics = {
            contentDescription = if (detail == null) title else "$title, $detail"
            onClick(label = openLabel) { onClick(); true }
        },
    )
}

private fun categoryIcon(category: Category): Int = when (category) {
    Category.ARCHIVES -> R.drawable.ic_archive
    else -> R.drawable.ic_file
}

/** Ligne de la racine : un seul élément TalkBack, dont le texte et les actions sont fournis par [semantics]. */
@Composable
private fun RootRow(
    icon: Int,
    title: String,
    detail: String?,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    semantics: SemanticsPropertyReceiver.() -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester).focusable() else Modifier)
                .clearAndSetSemantics(semantics)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!detail.isNullOrEmpty()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 56.dp))
    }
}
