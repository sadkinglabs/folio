package dev.sadking.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sadking.folio.data.*

/** Shared rhythm, rather than per-screen dimensions and nested decorative cards. */
internal object FolioSpace {
    val small = 8.dp
    val gap = 12.dp
    val item = 16.dp
    val page = 20.dp
    val section = 24.dp
    val coverShape = RoundedCornerShape(8.dp)
    val panelShape = RoundedCornerShape(20.dp)
}

@Composable
internal fun BookHero(book: Book) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.3f
        if (stacked)
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
            ) {
                Cover(book, Modifier.width(132.dp).aspectRatio(0.68f))
                BookBibliography(book)
            }
        else
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FolioSpace.page),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(book, Modifier.width(116.dp).aspectRatio(0.68f))
                Column(Modifier.weight(1f)) { BookBibliography(book) }
            }
    }
}

@Composable
private fun BookBibliography(book: Book) {
    Column(verticalArrangement = Arrangement.spacedBy(FolioSpace.small)) {
        Text(book.title, style = MaterialTheme.typography.headlineSmall)
        Text(
            book.authorLabel.ifBlank { "Unknown author" },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (book.series.isNotBlank())
            Text(
                listOf(book.series, book.seriesIndex)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
    }
}

@Composable
internal fun BookPrimaryActions(
    fileLabel: String,
    onFile: () -> Unit,
    onProgress: () -> Unit,
    enabled: Boolean,
    progressEnabled: Boolean = enabled,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 340.dp || LocalDensity.current.fontScale > 1.3f)
            Column(verticalArrangement = Arrangement.spacedBy(FolioSpace.small)) {
                Button(onClick = onFile, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(fileLabel)
                }
                FilledTonalButton(
                    onClick = onProgress,
                    enabled = progressEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Update progress")
                }
            }
        else
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap)) {
                Button(onClick = onFile, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(fileLabel)
                }
                FilledTonalButton(
                    onClick = onProgress,
                    enabled = progressEnabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Update progress")
                }
            }
    }
}

internal fun displayDeviceState(signals: BookSignals): String =
    when (signals.device) {
        "On Kindle" -> "Verified on Kindle"
        "Needs update" -> "EPUB edited since transfer"
        "Phone only",
        "Not sent" -> "EPUB on phone"
        else -> signals.device
    }

internal fun observationDate(time: Long): String =
    java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.SHORT,
            java.text.DateFormat.SHORT,
        )
        .format(java.util.Date(time))

@Composable
internal fun BookStateSummary(signals: BookSignals) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = FolioSpace.panelShape) {
        Column(
            Modifier.fillMaxWidth().padding(FolioSpace.item),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            Text(signals.reading, style = MaterialTheme.typography.titleSmall)
            signals.progress?.let {
                LinearProgressIndicator(
                    progress = { it.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${(it * 100).toInt()}% · saved reading progress",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                displayDeviceState(signals),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            signals.deviceVerifiedAt?.let {
                Text(
                    "Last verified ${observationDate(it)} · refresh to check the device",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            signals.readingFetchedAt?.let {
                Text(
                    "Reading data retrieved ${observationDate(it)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            signals.hardcover?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
internal fun BookListsSummary(names: List<String>, onManage: () -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Lists", style = MaterialTheme.typography.titleMedium)
            Text(
                names.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: "No custom lists yet",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onManage, enabled = enabled) { Text("Manage lists") }
    }
}
