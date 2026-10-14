@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package dev.sadking.folio.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import dev.sadking.folio.*
import dev.sadking.folio.R
import dev.sadking.folio.core.*
import dev.sadking.folio.data.*
import dev.sadking.folio.device.*

@Composable
fun CollectionsScreen(
    shelves: List<Shelf>,
    members: List<Membership>,
    onCreate: (String) -> Unit,
    onOpen: (String) -> Unit,
    busy: Boolean,
    counts: Map<String, Int> = emptyMap(),
    onSmartCreate: (String, String, String) -> Unit = { name, _, _ -> onCreate(name) },
    onSync: (Shelf) -> Unit = {},
    onPolicy: (Shelf, Boolean) -> Unit = { _, _ -> },
    onDiscover: (() -> Unit)? = null,
    onReadingList: (ReadingList) -> Unit = {},
    readingCounts: Map<ReadingList, Int> = emptyMap(),
    onManage: ((Shelf) -> Unit)? = null,
    previews: Map<String, List<Book>> = emptyMap(),
) {
    var transferId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    // Rule field identifiers are stored in the database; only their display text is translated.
    var field by rememberSaveable { mutableStateOf("Manual") }
    var value by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        PageHeading(stringResource(R.string.lists_title), stringResource(R.string.lists_subtitle)) {
            FilledTonalIconButton(onClick = { creating = true }, enabled = !busy) {
                Icon(Icons.Outlined.Add, stringResource(R.string.lists_create))
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.lists_pinned),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(ReadingList.entries, key = { "pinned-${it.name}" }) { list ->
                FilledTonalButton(
                    onClick = { onReadingList(list) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Icon(
                        when (list) {
                            ReadingList.WANT_TO_READ -> Icons.Outlined.BookmarkBorder
                            ReadingList.READING -> Icons.Outlined.AutoStories
                            ReadingList.FINISHED -> Icons.Outlined.DoneAll
                        },
                        null,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(list.title, style = MaterialTheme.typography.titleMedium)
                    }
                    val listCount = readingCounts[list] ?: 0
                    Text(
                        pluralStringResource(R.plurals.lists_book_count, listCount, listCount),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Outlined.ChevronRight, null)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.lists_yours),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (onDiscover != null)
                        TextButton(onClick = onDiscover, enabled = !busy) {
                            Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.lists_discover))
                        }
                }
            }
            if (shelves.isEmpty())
                item {
                    Text(
                        stringResource(R.string.lists_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { creating = true }, enabled = !busy) {
                        Text(stringResource(R.string.lists_create))
                    }
                }
            items(shelves, key = { it.id }) { shelf ->
                OutlinedCard(
                    modifier =
                        Modifier.fillMaxWidth()
                            .combinedClickable(
                                onClick = { onOpen(shelf.id) },
                                onLongClick = { onManage?.invoke(shelf) },
                            ),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        previews[shelf.id]?.firstOrNull()?.let {
                            Cover(it, Modifier.width(44.dp).aspectRatio(0.68f))
                        }
                            ?: Icon(
                                Icons.Outlined.CollectionsBookmark,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                            )

                        Column(Modifier.weight(1f)) {
                            Text(shelf.name, style = MaterialTheme.typography.titleMedium)
                            val shelfCount =
                                counts[shelf.id] ?: members.count { it.shelfId == shelf.id }
                            Text(
                                pluralStringResource(
                                    R.plurals.lists_book_count,
                                    shelfCount,
                                    shelfCount,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (shelf.ruleField != "Manual") {
                                val ruleLabel = libraryRuleLabel(shelf.ruleField)
                                val ruleValue =
                                    if (shelf.ruleField == "Status")
                                        libraryStatusLabel(shelf.ruleValue)
                                    else shelf.ruleValue
                                Text(
                                    if (ruleValue.isBlank()) ruleLabel
                                    else stringResource(R.string.lists_rule, ruleLabel, ruleValue),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { transferId = shelf.id }, enabled = !busy) {
                            Icon(Icons.Outlined.TabletAndroid, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.lists_transfer_options))
                        }
                        Spacer(Modifier.weight(1f))
                        if (shelf.syncEnabled)
                            Icon(
                                Icons.Outlined.Sync,
                                stringResource(R.string.lists_in_sync),
                                Modifier.size(20.dp),
                            )
                        if (onManage != null)
                            IconButton(onClick = { onManage(shelf) }, enabled = !busy) {
                                Icon(
                                    Icons.Outlined.MoreVert,
                                    stringResource(R.string.lists_manage, shelf.name),
                                )
                            }
                    }
                }
            }
        }
    }
    shelves
        .firstOrNull { it.id == transferId }
        ?.let { shelf ->
            ModalBottomSheet(onDismissRequest = { transferId = null }) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(shelf.name, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.send_list_explainer),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = {
                            transferId = null
                            onSync(shelf)
                        },
                        enabled =
                            !busy &&
                                (counts[shelf.id] ?: members.count { it.shelfId == shelf.id }) > 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.send_list))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val syncToggle = stringResource(R.string.lists_sync_toggle)
                        Column(Modifier.weight(1f)) {
                            Text(syncToggle, style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.lists_sync_toggle_hint),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            shelf.syncEnabled,
                            { onPolicy(shelf, it) },
                            enabled = !busy,
                            modifier = Modifier.semantics { contentDescription = syncToggle },
                        )
                    }
                }
            }
        }
    if (creating)
        ModalBottomSheet(onDismissRequest = { creating = false }) {
            Column(
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SectionHeading(
                    stringResource(R.string.lists_new_title),
                    stringResource(R.string.lists_new_subtitle),
                )
                EditorField(
                    stringResource(R.string.lists_name),
                    name,
                    { name = it.take(200) },
                    enabled = !busy,
                    error = shelves.any { it.name.equals(name.trim(), true) },
                    supporting =
                        if (shelves.any { it.name.equals(name.trim(), true) })
                            stringResource(R.string.lists_name_taken)
                        else null,
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LibraryRules.fields.forEach { option ->
                        FilterChip(
                            field == option,
                            {
                                field = option
                                value = ""
                            },
                            label = { Text(libraryRuleLabel(option)) },
                            enabled = !busy,
                        )
                    }
                }
                if (field !in listOf("Manual", "Missing on Kindle"))
                    EditorField(
                        libraryRuleLabel(field),
                        value,
                        { value = it },
                        enabled = !busy,
                        supporting =
                            if (field == "Status") stringResource(R.string.lists_rule_value_status)
                            else if (field == "Language")
                                stringResource(R.string.lists_rule_value_language)
                            else stringResource(R.string.lists_rule_value_other),
                    )
                if (field == "Missing on Kindle")
                    Text(
                        stringResource(R.string.lists_rule_missing_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                Button(
                    onClick = {
                        onSmartCreate(name.trim(), field, value.trim())
                        name = ""
                        value = ""
                        field = "Manual"
                        creating = false
                    },
                    enabled =
                        !busy &&
                            name.isNotBlank() &&
                            shelves.none { it.name.equals(name.trim(), true) } &&
                            (field in listOf("Manual", "Missing on Kindle") || value.isNotBlank()),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.lists_create))
                }
            }
        }
}
