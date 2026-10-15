@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.DiscoveryViewModel
import dev.sadking.folio.R
import dev.sadking.folio.data.*
import java.io.File

internal const val DISCOVERY_SCREEN_TAG = "discovery-screen"
internal const val DISCOVERY_CAMERA_TAG = "discovery-camera"
internal const val DISCOVERY_PHOTO_TAG = "discovery-photo"
internal const val DISCOVERY_QUERY_TAG = "discovery-query"
internal const val DISCOVERY_FIND_TAG = "discovery-find"

/**
 * Scan a cover: a full-screen route from the Add sheet. Two tiles (camera, photo), the recognised
 * text in a field, the matches as rows, and a sheet with the cover to add one to Want to Read.
 */
@Composable
fun DiscoveryScreen(
    model: DiscoveryViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit = {},
    onHardcoverLookup: (String) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
) {
    val context = LocalContext.current
    val state by model.state.collectAsStateWithLifecycle()
    val query by model.query.collectAsStateWithLifecycle()
    val candidates by model.candidates.collectAsStateWithLifecycle()
    val username by model.username.collectAsStateWithLifecycle()
    val cover by model.cover.collectAsStateWithLifecycle()
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<MetadataCandidate?>(null) }
    val noCamera = stringResource(R.string.discovery_no_camera)
    val photoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) model.photo(uri)
        }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok) cameraUri?.let { model.photo(Uri.parse(it)) }
        }
    DiscoveryContent(
        query = query,
        onQuery = model::setQuery,
        candidates = candidates,
        busy = state.busy,
        error = state.message.takeIf { state.failed },
        hardcover = username.isNotBlank(),
        onBack = onBack,
        onCamera = {
            val directory = File(context.cacheDir, "photos").apply { mkdirs() }
            val file = File(directory, "cover.jpg")
            cameraUri =
                FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
                    .toString()
            try {
                camera.launch(Uri.parse(cameraUri))
            } catch (e: android.content.ActivityNotFoundException) {
                model.report(noCamera)
            }
        },
        onPhoto = {
            photoPicker.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        },
        onFind = { model.search() },
        onHardcover = { onHardcoverLookup(query) },
        onChoose = { selected = it },
        contentPadding = contentPadding,
    )
    selected?.let { candidate ->
        ConfirmSheet(
            covers = listOf(coverStub(candidate.title, coverPath = cover)),
            title = candidate.title,
            body =
                listOf(
                        candidate.authors.joinToString(", "),
                        stringResource(R.string.discovery_save_body),
                    )
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
            confirmLabel = stringResource(R.string.discovery_add_want_to_read),
            dismissLabel = stringResource(R.string.shell_cancel),
            busy = state.busy,
            onConfirm = {
                model.save(candidate, onSaved)
                selected = null
            },
            onDismiss = { selected = null },
        )
    }
}

@Composable
internal fun DiscoveryContent(
    query: String,
    onQuery: (String) -> Unit,
    candidates: List<MetadataCandidate>,
    busy: Boolean,
    error: String?,
    hardcover: Boolean,
    onBack: () -> Unit,
    onCamera: () -> Unit,
    onPhoto: () -> Unit,
    onFind: () -> Unit,
    onHardcover: () -> Unit,
    onChoose: (MetadataCandidate) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    Column(
        Modifier.fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .testTag(DISCOVERY_SCREEN_TAG)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = FolioSpace.small, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.shell_back))
            }
            Text(
                stringResource(R.string.discovery_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            InfoAction(stringResource(R.string.discovery_privacy))
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(
            Modifier.padding(horizontal = FolioSpace.page, vertical = FolioSpace.gap),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap)) {
                TonalTile(
                    stringResource(R.string.discovery_take_photo),
                    Icons.Outlined.PhotoCamera,
                    Modifier.weight(1f).testTag(DISCOVERY_CAMERA_TAG),
                    enabled = !busy,
                    onClick = onCamera,
                )
                TonalTile(
                    stringResource(R.string.discovery_choose_photo),
                    Icons.Outlined.Image,
                    Modifier.weight(1f).testTag(DISCOVERY_PHOTO_TAG),
                    enabled = !busy,
                    onClick = onPhoto,
                )
            }
            OutlinedTextField(
                query,
                onQuery,
                label = { Text(stringResource(R.string.discovery_query_label)) },
                modifier = Modifier.fillMaxWidth().testTag(DISCOVERY_QUERY_TAG),
                minLines = 2,
                maxLines = 4,
                enabled = !busy,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(FolioSpace.small)) {
                Button(
                    onClick = onFind,
                    enabled = !busy && query.isNotBlank(),
                    modifier = Modifier.testTag(DISCOVERY_FIND_TAG),
                ) {
                    Text(stringResource(R.string.discovery_find))
                }
                if (hardcover)
                    FilledTonalButton(
                        onClick = onHardcover,
                        enabled = !busy && query.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.discovery_find_on_hardcover))
                    }
            }
            if (error != null)
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            candidates.forEach { candidate ->
                ResultRow(
                    candidate.title,
                    candidate.authors.joinToString(", "),
                    enabled = !busy,
                    onClick = { onChoose(candidate) },
                ) {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(FolioSpace.section))
        }
    }
}
