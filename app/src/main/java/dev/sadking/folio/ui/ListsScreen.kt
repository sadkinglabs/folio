@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package dev.sadking.folio.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.core.LibraryRules
import dev.sadking.folio.data.*
import kotlin.math.abs

internal const val LISTS_GRID_TAG = "lists-grid"
internal const val LISTS_NEW_TAG = "lists-new"
internal const val LIST_DETAIL_TAG = "list-detail"
internal const val LIST_EDITOR_TAG = "list-editor"
internal const val LIST_EDITOR_SAVE_TAG = "list-editor-save"

/** The test tag of one list card. */
internal fun listCardTag(id: String) = "list-card-$id"

internal const val LISTS_NEW_CARD_TAG = "lists-new-card"

/**
 * The Lists tab: a serif title, "+", and a two-column grid of [ListCardView]s, the three system
 * lists first. Tap opens the list; long-press on a user list opens its menu. The grid scrolls under
 * the glass bar and pads its end by [LocalContentBottomPadding].
 */
@Composable
internal fun ListsScreen(
    cards: List<ListCard>,
    busy: Boolean,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onEdit: (Shelf) -> Unit = {},
    onDelete: (Shelf) -> Unit = {},
    onScan: (() -> Unit)? = null,
) {
    val large = LocalDensity.current.fontScale > 1.5f
    val userLists = cards.any { it.shelf != null }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth()
                .padding(start = FolioSpace.page, end = FolioSpace.gap, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.lists_title),
                style =
                    if (large) MaterialTheme.typography.headlineSmall
                    else MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            if (onScan != null) {
                val scan = stringResource(R.string.library_wanted_empty_scan)
                Tooltipped(scan) {
                    IconButton(onClick = onScan, enabled = !busy) {
                        Icon(Icons.Outlined.PhotoCamera, scan)
                    }
                }
            }
            val new = stringResource(R.string.lists_new_title)
            Tooltipped(new) {
                FilledTonalIconButton(
                    onClick = onNew,
                    enabled = !busy,
                    modifier = Modifier.testTag(LISTS_NEW_TAG),
                ) {
                    Icon(Icons.Outlined.Add, new)
                }
            }
        }
        // Two columns on a phone; one at large text sizes, so names never break mid-word.
        val fontScale = LocalDensity.current.fontScale
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp * fontScale.coerceIn(1f, 1.4f)),
            modifier = Modifier.weight(1f).testTag(LISTS_GRID_TAG),
            contentPadding =
                PaddingValues(
                    start = FolioSpace.page,
                    end = FolioSpace.page,
                    top = FolioSpace.item,
                    bottom = LocalContentBottomPadding.current + FolioSpace.section,
                ),
            horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.gap),
        ) {
            items(cards, key = { it.id }) { card ->
                ListCardView(
                    card,
                    busy = busy,
                    onClick = { onOpen(card.id) },
                    onEdit = onEdit,
                    onDelete = onDelete,
                )
            }
            // Until there are lists of your own, the next tile offers one, the same size as the
            // rest so the grid stays even.
            if (!userLists) item(key = "new") { NewListCard(enabled = !busy, onClick = onNew) }
        }
    }
}

/** Lines kept for a card's name: two in the two-column grid, one when text is large. */
@Composable private fun listTitleLines() = if (LocalDensity.current.fontScale > 1.3f) 1 else 2

/** The "New list" tile: a dashed outline shaped like a list card, with the same three rows. */
@Composable
internal fun NewListCard(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier
            .fillMaxWidth()
            .clip(FolioSpace.panelShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .drawBehind {
                drawRoundRect(
                    outline,
                    cornerRadius = CornerRadius(20.dp.toPx()),
                    style =
                        Stroke(
                            width = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
                        ),
                )
            }
            .testTag(LISTS_NEW_CARD_TAG),
        shape = FolioSpace.panelShape,
        color = Color.Transparent,
    ) {
        Column(Modifier.padding(FolioSpace.item)) {
            Box(Modifier.fillMaxWidth().height(104.dp), contentAlignment = Alignment.Center) {
                Surface(
                    Modifier.size(56.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        Icons.Outlined.Add,
                        null,
                        Modifier.padding(14.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(FolioSpace.gap))
            Text(
                stringResource(R.string.lists_new_title),
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif),
                minLines = listTitleLines(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.rd_lists_new_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The ground of a list card or banner: the panel tone, warmed by the first cover at 14%. */
@Composable
private fun listGround(card: ListCard): Color {
    val panel = MaterialTheme.colorScheme.surfaceContainer
    val first = card.covers.firstOrNull() ?: return MaterialTheme.colorScheme.surfaceContainerLow
    return lerp(panel, coverToneColor(first, towardsSurface = 0f), 0.14f)
}

/**
 * One list: a tinted panel with a fan of its covers, the name in serif and the count. A small mark
 * at the end of the count line says when a list fills by a rule.
 */
@Composable
internal fun ListCardView(
    card: ListCard,
    busy: Boolean,
    onClick: () -> Unit,
    onEdit: (Shelf) -> Unit,
    onDelete: (Shelf) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val shelf = card.shelf
    val count = pluralStringResource(R.plurals.lists_book_count, card.count, card.count)
    val options = stringResource(R.string.lists_manage, card.name)
    val haptics = rememberHaptics()
    Box(modifier) {
        Surface(
            Modifier.fillMaxWidth()
                .clip(FolioSpace.panelShape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick =
                        if (shelf != null)
                            ({
                                haptics.longPress()
                                menu = true
                            })
                        else null,
                )
                .testTag(listCardTag(card.id))
                .semantics(mergeDescendants = true) {
                    if (shelf != null)
                        customActions =
                            listOf(
                                CustomAccessibilityAction(options) {
                                    menu = true
                                    true
                                }
                            )
                },
            shape = FolioSpace.panelShape,
            color = listGround(card),
        ) {
            Column(Modifier.padding(FolioSpace.item)) {
                CoverMosaic(
                    card.covers,
                    Modifier.fillMaxWidth().height(104.dp),
                    coverWidth = 52.dp,
                )
                Spacer(Modifier.height(FolioSpace.gap))
                // Two lines' room while cards sit side by side, so a row is even whether a name
                // wraps or not; one card per row needs no room it will not use.
                Text(
                    card.name,
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif),
                    minLines = listTitleLines(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        count,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (card.rule != null) {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            stringResource(R.string.rd_list_by_rule),
                            Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
        if (shelf != null)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rd_action_edit)) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                    enabled = !busy,
                    onClick = {
                        menu = false
                        onEdit(shelf)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_list_delete)) },
                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                    enabled = !busy,
                    onClick = {
                        menu = false
                        onDelete(shelf)
                    },
                    colors =
                        MenuDefaults.itemColors(
                            textColor = MaterialTheme.colorScheme.error,
                            leadingIconColor = MaterialTheme.colorScheme.error,
                        ),
                )
            }
    }
}

/**
 * Up to four covers fanned out, each lifted and turned a few degrees; fewer stack with a slight
 * offset; none shows a bookmark glyph on a tonal disc. Covers are decoded as thumbnails and read as
 * decoration: the card's name and count carry the meaning.
 */
@Composable
internal fun CoverMosaic(covers: List<Book>, modifier: Modifier = Modifier, coverWidth: Dp) {
    Box(modifier.clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        if (covers.isEmpty()) {
            Surface(
                Modifier.size(coverWidth * 1.1f),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    Icons.Outlined.CollectionsBookmark,
                    null,
                    Modifier.padding(coverWidth * 0.28f),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            return@Box
        }
        val shown = covers.take(MOSAIC_COVERS)
        val n = shown.size
        // Four covers spread into a fan; a smaller set sits as a loose stack.
        val spread = if (n == MOSAIC_COVERS) coverWidth * 0.46f else 14.dp
        val tilt = if (n == MOSAIC_COVERS) 4f else 5f
        shown.forEachIndexed { i, book ->
            val slot = i - (n - 1) / 2f
            Box(
                Modifier.offset(x = spread * slot, y = 3.dp * abs(slot))
                    .width(coverWidth)
                    .aspectRatio(FolioSpace.COVER_RATIO)
                    .graphicsLayer {
                        rotationZ = tilt * slot
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                    }
                    .then(lift(FolioSpace.coverShape, 3.dp))
                    .clip(FolioSpace.coverShape)
            ) {
                Cover(book, Modifier.fillMaxSize(), thumbnail = true)
            }
        }
    }
}

/**
 * One list's books, as a route above the Lists tab. A tinted banner carries the mosaic, the name in
 * serif and the count; user lists get Edit. Below, the Library grid with its sort and layout
 * controls. Rule lists show their rule as a chip.
 */
@Composable
internal fun ListDetailScreen(
    card: ListCard,
    selection: Set<String>,
    busy: Boolean,
    onBack: () -> Unit,
    onOpen: (Book) -> Unit,
    onSelect: (String) -> Unit,
    onClearSelection: () -> Unit,
    onAddFromLibrary: () -> Unit,
    onEdit: ((Shelf) -> Unit)? = null,
    signals: Map<String, BookSignals> = emptyMap(),
    fileIds: Set<String> = card.books.map { it.id }.toSet(),
    progress: Map<String, Double> = emptyMap(),
    currentId: String? = null,
    onContext: ((Book) -> Unit)? = null,
) {
    val large = LocalDensity.current.fontScale > 1.5f
    val shelf = card.shelf
    // The list's page header: Back, the page actions (view, Edit) and the banner. The view
    // toggle comes from the grid, which owns the layout choice; an empty list has none.
    val pageHeader: @Composable (viewToggle: (@Composable () -> Unit)?) -> Unit = { viewToggle ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = FolioSpace.small, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.library_back))
            }
            Spacer(Modifier.weight(1f))
            viewToggle?.invoke()
            if (shelf != null && onEdit != null) {
                val edit = stringResource(R.string.rd_action_edit)
                Tooltipped(edit) {
                    IconButton(onClick = { onEdit(shelf) }, enabled = !busy) {
                        Icon(Icons.Outlined.Edit, edit)
                    }
                }
            }
        }
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = FolioSpace.page),
            shape = FolioSpace.panelShape,
            color = listGround(card),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(FolioSpace.item),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverMosaic(card.covers, Modifier.width(132.dp).height(96.dp), coverWidth = 48.dp)
                Spacer(Modifier.width(FolioSpace.item))
                Column(Modifier.weight(1f)) {
                    Text(
                        card.name,
                        style =
                            if (large) MaterialTheme.typography.headlineSmall
                            else MaterialTheme.typography.headlineMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        pluralStringResource(R.plurals.lists_book_count, card.count, card.count),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    card.rule?.let { (field, value) ->
                        val label = libraryRuleLabel(field)
                        val shown = if (field == "Status") libraryStatusLabel(value) else value
                        SuggestionChip(
                            onClick = {},
                            enabled = false,
                            label = {
                                Text(
                                    if (shown.isBlank()) label
                                    else stringResource(R.string.lists_rule, label, shown),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            icon = { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp)) },
                            colors =
                                SuggestionChipDefaults.suggestionChipColors(
                                    disabledContainerColor =
                                        MaterialTheme.colorScheme.surfaceContainerHigh,
                                    disabledLabelColor = MaterialTheme.colorScheme.onSurface,
                                    disabledIconContentColor = MaterialTheme.colorScheme.tertiary,
                                ),
                            border = null,
                            modifier = Modifier.padding(top = FolioSpace.small),
                        )
                    }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().testTag(LIST_DETAIL_TAG)) {
        if (card.books.isEmpty()) {
            pageHeader(null)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                EmptyState(
                    stringResource(R.string.rd_list_empty_title),
                    stringResource(R.string.rd_list_empty_body),
                    Icons.Outlined.CollectionsBookmark,
                    onAddFromLibrary,
                    actionLabel = stringResource(R.string.rd_list_empty_action),
                    actionEnabled = !busy,
                    illustration = FolioIllustration.Books,
                )
            }
        } else
            LibraryScreen(
                card.books,
                selection,
                Modifier.weight(1f),
                onOpen,
                onSelect = onSelect,
                filterName = card.name,
                header = { toggle -> pageHeader(toggle) },
                busy = busy,
                signals = signals,
                fileIds = fileIds,
                progress = progress,
                currentId = currentId,
                onContext = onContext,
                onClearSelection = onClearSelection,
            )
    }
}

private val manualFields = listOf("Manual", "Missing on Kindle")

/**
 * The list editor, for a new list ([shelf] null) or an existing one. Name, Manual or Rule, the
 * rule's field and value. Lists sync with the e-reader's KOReader collections by themselves. Save
 * is the one filled button; Delete is an icon in the title row. The rule-change note appears only
 * when a rule list is switched to Manual.
 */
@Composable
internal fun ListEditorSheet(
    shelf: Shelf?,
    takenNames: List<String>,
    busy: Boolean,
    error: String?,
    onCreate: (name: String, field: String, value: String) -> Unit,
    onSave: (Shelf) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val key = shelf?.id ?: "new"
    var name by rememberSaveable(key) { mutableStateOf(shelf?.name ?: "") }
    // Rule field identifiers are stored in the database; only their display text is translated.
    var field by rememberSaveable(key) { mutableStateOf(shelf?.ruleField ?: "Manual") }
    var value by rememberSaveable(key) { mutableStateOf(shelf?.ruleValue ?: "") }
    val taken = takenNames.any {
        it.equals(name.trim(), true) && !it.equals(shelf?.name ?: "", true)
    }
    val valid = name.isNotBlank() && !taken && (field in manualFields || value.isNotBlank())
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = FolioSpace.section, end = FolioSpace.section, bottom = 32.dp)
                .testTag(LIST_EDITOR_TAG),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        if (shelf == null) R.string.lists_new_title
                        else R.string.library_list_edit_title
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (onDelete != null)
                    IconButton(onClick = onDelete, enabled = !busy) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            stringResource(R.string.library_list_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
            }
            EditorField(
                stringResource(R.string.library_list_name),
                name,
                { name = it.take(120) },
                enabled = !busy,
                error = taken,
                supporting = if (taken) stringResource(R.string.lists_name_taken) else null,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = field == "Manual",
                    onClick = {
                        field = "Manual"
                        value = ""
                    },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    enabled = !busy,
                    label = { Text(stringResource(R.string.library_rule_manual)) },
                )
                SegmentedButton(
                    selected = field != "Manual",
                    onClick = { if (field == "Manual") field = "Status" },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    enabled = !busy,
                    label = { Text(stringResource(R.string.library_list_rule)) },
                )
            }
            if (field != "Manual") {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
                ) {
                    LibraryRules.fields
                        .filter { it != "Manual" }
                        .forEach { option ->
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
                if (field !in manualFields)
                    EditorField(
                        stringResource(R.string.library_list_rule_value),
                        value,
                        { value = it },
                        enabled = !busy,
                        supporting =
                            stringResource(
                                when (field) {
                                    "Status" -> R.string.lists_rule_value_status
                                    "Language" -> R.string.lists_rule_value_language
                                    else -> R.string.lists_rule_value_other
                                }
                            ),
                    )
                else
                    Text(
                        stringResource(R.string.lists_rule_missing_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            if (shelf != null && shelf.ruleField != "Manual" && field == "Manual")
                Text(
                    stringResource(R.string.library_list_rule_change_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            Button(
                onClick = {
                    if (shelf == null) onCreate(name.trim(), field, value.trim())
                    else
                        onSave(
                            shelf.copy(
                                name = name.trim(),
                                ruleField = field,
                                ruleValue = value.trim(),
                            )
                        )
                },
                enabled = !busy && valid,
                modifier =
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(LIST_EDITOR_SAVE_TAG),
            ) {
                Text(
                    stringResource(
                        if (shelf == null) R.string.lists_create else R.string.library_list_save
                    )
                )
            }
        }
    }
}
