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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import dev.sadking.folio.*
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
    var field by rememberSaveable { mutableStateOf("Manual") }
    var value by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        PageHeading("Lists", "Reading plans and collections") {
            FilledTonalIconButton(onClick = { creating = true }, enabled = !busy) {
                Icon(Icons.Outlined.Add, "Create list")
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Pinned", style = MaterialTheme.typography.titleMedium) }
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
                    Text(
                        "${readingCounts[list] ?: 0} books",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Outlined.ChevronRight, null)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Your lists",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (onDiscover != null)
                        TextButton(onClick = onDiscover, enabled = !busy) {
                            Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Discover")
                        }
                }
            }
            if (shelves.isEmpty())
                item {
                    Text(
                        "Create a list for a topic, series or reading plan. Add books from their details or a library selection.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { creating = true }, enabled = !busy) {
                        Text("Create list")
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
                            Text(
                                "${counts[shelf.id] ?: members.count { it.shelfId == shelf.id }} books",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (shelf.ruleField != "Manual")
                                Text(
                                    "${shelf.ruleField} ${shelf.ruleValue}",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { transferId = shelf.id }, enabled = !busy) {
                            Icon(Icons.Outlined.TabletAndroid, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Transfer options")
                        }
                        Spacer(Modifier.weight(1f))
                        if (shelf.syncEnabled)
                            Icon(Icons.Outlined.Sync, "Included in sync", Modifier.size(20.dp))
                        if (onManage != null)
                            IconButton(onClick = { onManage(shelf) }, enabled = !busy) {
                                Icon(Icons.Outlined.MoreVert, "Edit or delete ${shelf.name}")
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
                        "Only attached EPUBs can be sent. Review the transfer before anything is uploaded.",
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
                        Text("Preview transfer")
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Include in one-tap sync",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "Uses this list's attached EPUBs",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            shelf.syncEnabled,
                            { onPolicy(shelf, it) },
                            enabled = !busy,
                            modifier =
                                Modifier.semantics {
                                    contentDescription = "Include in one-tap sync"
                                },
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
                    "New list",
                    "Choose books yourself, or build a collection from a rule.",
                )
                EditorField(
                    "List name",
                    name,
                    { name = it.take(200) },
                    enabled = !busy,
                    error = shelves.any { it.name.equals(name.trim(), true) },
                    supporting =
                        if (shelves.any { it.name.equals(name.trim(), true) })
                            "A list with this name already exists"
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
                            label = { Text(option) },
                            enabled = !busy,
                        )
                    }
                }
                if (field !in listOf("Manual", "Missing on Kindle"))
                    EditorField(
                        "$field equals",
                        value,
                        { value = it },
                        enabled = !busy,
                        supporting =
                            if (field == "Status") "Unread, Reading, Finished or Abandoned"
                            else if (field == "Language")
                                "Use the book's language code, such as en or es"
                            else "Match the value stored in your books",
                    )
                if (field == "Missing on Kindle")
                    Text(
                        "Uses the last verified Kindle inventory. Sync refreshes availability before transferring.",
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
                    Text("Create list")
                }
            }
        }
}
