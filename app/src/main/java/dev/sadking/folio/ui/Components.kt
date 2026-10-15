@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package dev.sadking.folio.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.sadking.folio.*
import dev.sadking.folio.R
import dev.sadking.folio.core.*
import dev.sadking.folio.data.*
import dev.sadking.folio.device.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

@Composable
internal fun StatusSurface(op: Operation) {
    Surface(
        color =
            if (op.failed) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).semantics {
                liveRegion = LiveRegionMode.Polite
            }
        ) {
            Text(op.message, style = MaterialTheme.typography.bodyMedium)
            if (op.busy) {
                Spacer(Modifier.height(8.dp))
                if (op.progress != null)
                    LinearProgressIndicator(
                        progress = { op.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * A transfer running in the background, shown above every screen except the device tab. It never
 * blocks the screen beneath it.
 */
@Composable
internal fun TransferBanner(job: SyncJob, onOpen: () -> Unit, onCancel: () -> Unit) {
    val total = job.totalBooks.coerceAtLeast(1)
    val progress =
        if (job.totalBytes > 0) (job.bytesSent.toFloat() / job.totalBytes).coerceIn(0f, 1f)
        else job.completedBooks.toFloat() / total
    Surface(onClick = onOpen, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when (job.state) {
                        "QUEUED" -> stringResource(R.string.transfer_waiting)
                        "CANCELLING" -> stringResource(R.string.transfer_stopping)
                        else ->
                            stringResource(
                                R.string.transfer_sending,
                                (job.completedBooks + 1).coerceAtMost(total),
                                total,
                            )
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                if (job.currentTitle.isNotBlank())
                    Text(
                        job.currentTitle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = onCancel, enabled = job.state != "CANCELLING") {
                Text(stringResource(R.string.transfer_cancel))
            }
        }
    }
}

internal val LocalRouteHeading = staticCompositionLocalOf { false }

@Composable
internal fun PageHeading(
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (!LocalRouteHeading.current) {
                Text(title, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(4.dp))
            }
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        trailing?.invoke()
    }
}

/**
 * The twelve hues a generated cover can take; a title always maps to the same one. Muted so the
 * serif title stays legible in both themes.
 */
private val generatedCoverHues =
    listOf(18f, 42f, 68f, 95f, 128f, 160f, 190f, 212f, 236f, 268f, 300f, 335f)

/** The ground of a generated cover: a hue from the title, lightness from the theme. */
@Composable
internal fun generatedCoverColor(title: String): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val hue = generatedCoverHues[Math.floorMod(title.trim().lowercase().hashCode(), 12)]
    return Color.hsl(hue, if (dark) 0.22f else 0.30f, if (dark) 0.26f else 0.82f)
}

/**
 * A cover: 6 dp corners, the artwork never cropped away from its 2:3 frame, and a 1 px highlight
 * near the left edge with a darker band beside it that reads as a spine. Books without artwork get
 * a typographic cover on a ground derived from the title.
 */
@Composable
fun Cover(book: Book, modifier: Modifier = Modifier) {
    val image by
        produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, book.coverPath) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching { CoverImages.load(book.coverPath)?.asImageBitmap() }.getOrNull()
                }
        }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val spine = Color.White.copy(alpha = if (dark) 0.12f else 0.25f)
    val shade = Color.Black.copy(alpha = if (dark) 0.28f else 0.10f)
    Surface(
        modifier.clip(FolioSpace.coverShape).drawWithContent {
            drawContent()
            val x = 3.dp.toPx()
            drawRect(shade, topLeft = Offset(x + 1f, 0f), size = Size(2.dp.toPx(), size.height))
            drawRect(spine, topLeft = Offset(x, 0f), size = Size(1f, size.height))
        },
        color =
            if (image != null) MaterialTheme.colorScheme.surfaceContainerHigh
            else generatedCoverColor(book.title),
    ) {
        if (image != null)
            Image(
                image!!,
                stringResource(R.string.shell_cover_of, book.title),
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        else {
            // A generated cover is artwork: its title keeps one size, whatever the system font.
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides
                    Density(density.density, fontScale = 1f)
            ) {
                Column(
                    Modifier.fillMaxSize().padding(start = 14.dp, top = 14.dp, end = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HorizontalDivider(
                        Modifier.width(24.dp),
                        thickness = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Text(
                        book.title,
                        style =
                            (if (book.title.length > 28) MaterialTheme.typography.titleSmall
                                else MaterialTheme.typography.titleMedium)
                                .copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** What a cover stands for. The badge and the dashed frame depend on it. */
internal enum class CoverVariant {
    /** The library grid. */
    Grid,
    /** The "Reading" shelf: a smaller cover with its progress and percentage. */
    Shelf,
    /** A book wanted but not owned: dashed frame, 80% artwork. */
    Wanted,
    /** Waiting in the send queue. */
    Queued,
}

/**
 * A lifted cover with at most one badge at the top-right and, for a book in progress, a 3 dp
 * progress bar flush to its bottom edge. The one cover component for grids, shelves, lists and the
 * queue.
 */
@Composable
internal fun CoverTile(
    book: Book,
    modifier: Modifier = Modifier,
    variant: CoverVariant = CoverVariant.Grid,
    progress: Float? = null,
    badge: Pair<ImageVector, String>? = null,
    lifted: Boolean = true,
) {
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier
            .aspectRatio(FolioSpace.COVER_RATIO)
            .then(if (lifted) lift(FolioSpace.coverShape, 4.dp) else Modifier)
            .clip(FolioSpace.coverShape)
            .then(
                if (variant == CoverVariant.Wanted)
                    Modifier.drawWithContent {
                        drawContent()
                        val stroke = 1.5.dp.toPx()
                        drawRoundRect(
                            outline,
                            topLeft = Offset(stroke / 2, stroke / 2),
                            size = Size(size.width - stroke, size.height - stroke),
                            cornerRadius = CornerRadius(6.dp.toPx()),
                            style =
                                Stroke(
                                    stroke,
                                    pathEffect =
                                        PathEffect.dashPathEffect(
                                            floatArrayOf(6.dp.toPx(), 4.dp.toPx())
                                        ),
                                ),
                        )
                    }
                else Modifier
            )
    ) {
        Cover(
            book,
            Modifier.matchParentSize().alpha(if (variant == CoverVariant.Wanted) 0.8f else 1f),
        )
        badge?.let { (icon, description) ->
            Surface(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Icon(icon, description, Modifier.padding(3.dp))
            }
        }
        progress?.let { value ->
            LinearProgressIndicator(
                progress = { value.coerceIn(0f, 1f) },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

/**
 * Glass for bars and floating pills: the content behind, blurred 24 dp through Haze, a tint of
 * `surface`, and a 1 px hairline on the edge facing the content. Without a [hazeState] (previews,
 * tests, a surface with nothing behind it) the tint alone is drawn, near opaque, in the same
 * layout.
 */
@Composable
internal fun GlassSurface(
    hazeState: dev.chrisbanes.haze.HazeState?,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    /** Which edge faces the content: the top for a bottom bar, the bottom for a top bar. */
    hairlineAtTop: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val tint = FolioGlass.tint()
    val flat = FolioGlass.flatTint()
    val hairline = FolioGlass.hairline()
    val ground = MaterialTheme.colorScheme.surface
    Box(
        modifier
            .clip(shape)
            .then(
                if (hazeState != null)
                    Modifier.hazeBlur(
                        HazeInput.Sources(hazeState),
                        style =
                            HazeBlurStyle {
                                blurRadius(FolioGlass.blurRadius)
                                noiseFactor(0f)
                                backgroundColor(ground)
                                colorEffects(listOf(HazeColorEffect.tint(tint)))
                                fallbackColorEffect(HazeColorEffect.tint(flat))
                            },
                    )
                else Modifier.background(flat)
            )
            .drawWithContent {
                drawContent()
                val y = if (hairlineAtTop) 0.5f else size.height - 0.5f
                drawLine(hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            },
        content = content,
    )
}

/** The drawn vectors for empty states: 96 dp, two tones, no bitmap. */
internal enum class FolioIllustration {
    /** Three stacked books. */
    Books,
    /** A bookmark ribbon. */
    Bookmark,
    /** An e-reader outline. */
    Reader,
}

@Composable
internal fun Illustration(kind: FolioIllustration, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val container = MaterialTheme.colorScheme.primaryContainer
    val tertiary = MaterialTheme.colorScheme.tertiaryContainer
    Canvas(modifier.size(96.dp)) {
        val w = size.width
        val h = size.height
        val r = CornerRadius(w * 0.04f)
        when (kind) {
            FolioIllustration.Books -> {
                drawRoundRect(tertiary, Offset(w * 0.14f, h * 0.62f), Size(w * 0.72f, h * 0.16f), r)
                drawRoundRect(
                    container,
                    Offset(w * 0.22f, h * 0.44f),
                    Size(w * 0.60f, h * 0.16f),
                    r,
                )
                drawRoundRect(primary, Offset(w * 0.18f, h * 0.26f), Size(w * 0.56f, h * 0.16f), r)
                drawRect(container, Offset(w * 0.26f, h * 0.26f), Size(w * 0.03f, h * 0.16f))
            }
            FolioIllustration.Bookmark -> {
                drawRoundRect(
                    container,
                    Offset(w * 0.20f, h * 0.14f),
                    Size(w * 0.60f, h * 0.72f),
                    r,
                )
                val ribbon =
                    Path().apply {
                        moveTo(w * 0.56f, h * 0.14f)
                        lineTo(w * 0.72f, h * 0.14f)
                        lineTo(w * 0.72f, h * 0.58f)
                        lineTo(w * 0.64f, h * 0.50f)
                        lineTo(w * 0.56f, h * 0.58f)
                        close()
                    }
                drawPath(ribbon, primary)
            }
            FolioIllustration.Reader -> {
                drawRoundRect(primary, Offset(w * 0.22f, h * 0.10f), Size(w * 0.56f, h * 0.80f), r)
                drawRoundRect(
                    container,
                    Offset(w * 0.28f, h * 0.16f),
                    Size(w * 0.44f, h * 0.58f),
                    CornerRadius(w * 0.02f),
                )
                repeat(4) { line ->
                    drawRect(
                        primary.copy(alpha = 0.5f),
                        Offset(w * 0.33f, h * (0.24f + line * 0.11f)),
                        Size(w * (if (line == 3) 0.22f else 0.34f), h * 0.025f),
                    )
                }
            }
        }
    }
}

@Composable
internal fun EmptyState(
    title: String,
    message: String,
    icon: ImageVector,
    action: (() -> Unit)? = null,
    actionLabel: String = stringResource(R.string.shell_import_epub),
    actionEnabled: Boolean = true,
    /** When set, the drawn vector replaces [icon]. */
    illustration: FolioIllustration? = null,
    /** A second, quieter way out, as a text button under the first. */
    secondaryAction: (() -> Unit)? = null,
    secondaryLabel: String = "",
) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (illustration != null) Illustration(illustration)
        else Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = action, enabled = actionEnabled) { Text(actionLabel) }
        }
        if (secondaryAction != null)
            TextButton(onClick = secondaryAction, enabled = actionEnabled) {
                Text(secondaryLabel)
            }
    }
}

internal fun Modifier.toggleableCompat(
    value: Boolean,
    enabled: Boolean,
    onValue: (Boolean) -> Unit,
): Modifier =
    this.then(Modifier.clickable(enabled = enabled, role = Role.Checkbox) { onValue(!value) })
        .semantics {
            toggleableState =
                if (value) androidx.compose.ui.state.ToggleableState.On
                else androidx.compose.ui.state.ToggleableState.Off
        }

@Composable
internal fun DetailLine(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun EditorField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    multiline: Boolean = false,
    error: Boolean = false,
    enabled: Boolean = true,
    supporting: String? = null,
) {
    OutlinedTextField(
        value,
        onChange,
        Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = !multiline,
        minLines = if (multiline) 2 else 1,
        maxLines = if (multiline) 6 else 1,
        isError = error,
        enabled = enabled,
        supportingText = supporting?.let { { Text(it) } },
        shape = RoundedCornerShape(16.dp),
    )
}

/** Shared section rhythm and progressive disclosure across device, reading and book details. */
@Composable
internal fun SectionHeading(title: String, subtitle: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (subtitle.isNotBlank())
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

@Composable
internal fun ExpandableSection(
    title: String,
    summary: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val expansionState =
        stringResource(if (expanded) R.string.shell_expanded else R.string.shell_collapsed)
    OutlinedCard(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(summary) },
            trailingContent = {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            },
            modifier =
                Modifier.clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { stateDescription = expansionState },
        )
        if (expanded)
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
    }
}

@Composable
internal fun FeatureRow(
    title: String,
    summary: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(summary, style = MaterialTheme.typography.bodySmall) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
        modifier =
            Modifier.fillMaxWidth()
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .defaultMinSize(minHeight = 64.dp),
    )
}

@Composable
internal fun rememberOperationSnackbar(
    notices: kotlinx.coroutines.flow.Flow<Operation>
): SnackbarHostState {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(notices) {
        notices.collectLatest { notice ->
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(
                notice.message,
                withDismissAction = true,
                duration = if (notice.failed) SnackbarDuration.Long else SnackbarDuration.Short,
            )
        }
    }
    return host
}
