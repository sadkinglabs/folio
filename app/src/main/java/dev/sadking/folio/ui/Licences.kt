package dev.sadking.folio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R

internal const val LICENCES_TAG = "settings-licences-content"

/** The licences text's paragraphs, split on blank lines; blank paragraphs dropped. */
internal fun licenceParagraphs(text: String): List<String> =
    text
        .replace("\r\n", "\n")
        .split(Regex("\n\\s*\n"))
        .map { paragraph -> paragraph.lines().joinToString(" ") { it.trim() }.trim() }
        .filter { it.isNotEmpty() }

/**
 * Settings → About → Licences: every third-party part Librillo uses or downloads, with its licence,
 * and the dictionary's attribution word for word. The text lives in `res/raw/licences.txt` so it
 * reads the same in the app and in the source. Selectable, so a licence's address can be copied.
 */
@Composable
internal fun LicencesContent() {
    val resources = LocalResources.current
    val paragraphs =
        remember(resources) {
            licenceParagraphs(
                resources.openRawResource(R.raw.licences).bufferedReader().use {
                    it.readText()
                }
            )
        }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 32.dp).testTag(LICENCES_TAG),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
    ) {
        SheetHeading(
            stringResource(R.string.ad6_licences),
            stringResource(R.string.ad6_licences_body),
        )
        SelectionContainer {
            Column(
                Modifier.padding(horizontal = FolioSpace.section),
                verticalArrangement = Arrangement.spacedBy(FolioSpace.gap),
            ) {
                paragraphs.forEach { paragraph ->
                    Text(paragraph, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
