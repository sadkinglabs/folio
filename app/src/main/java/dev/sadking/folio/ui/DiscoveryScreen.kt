@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.sadking.folio.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sadking.folio.DiscoveryViewModel
import dev.sadking.folio.data.*
import java.io.File

@Composable
fun DiscoveryScreen(
    model: DiscoveryViewModel,
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {},
    onHardcoverLookup: (String) -> Unit = {},
) {
    val snackbar = rememberOperationSnackbar(model.notices)
    val context = LocalContext.current
    val state by model.state.collectAsStateWithLifecycle()
    val query by model.query.collectAsStateWithLifecycle()
    val candidates by model.candidates.collectAsStateWithLifecycle()
    val username by model.username.collectAsStateWithLifecycle()
    var connect by rememberSaveable { mutableStateOf(false) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<MetadataCandidate?>(null) }
    val photoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) model.photo(uri)
        }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok) cameraUri?.let { model.photo(Uri.parse(it)) }
        }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text("Discover books") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        TextButton(onClick = { connect = true }, enabled = !state.busy) {
                            Text(if (username.isBlank()) "Connect Hardcover" else "@$username")
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.busy)
                    Surface(
                        color =
                            if (state.failed) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(state.message)
                            if (state.busy)
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                    }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        Text(
                            "A book caught your eye?",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            "Photograph the cover, check the recognised text, then confirm a catalogue match. No EPUB needed.",
                            Modifier.padding(top = 8.dp),
                        )
                        Row(
                            Modifier.padding(top = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    val directory =
                                        File(context.cacheDir, "photos").apply { mkdirs() }
                                    val file = File(directory, "cover.jpg")
                                    cameraUri =
                                        FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.photos",
                                                file,
                                            )
                                            .toString()
                                    try {
                                        camera.launch(Uri.parse(cameraUri))
                                    } catch (e: android.content.ActivityNotFoundException) {
                                        model.report("No camera app found; choose a photo instead")
                                    }
                                },
                                enabled = !state.busy,
                            ) {
                                Icon(Icons.Outlined.PhotoCamera, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Take photo")
                            }
                            OutlinedButton(
                                onClick = {
                                    photoPicker.launch(
                                        androidx.activity.result.PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                                enabled = !state.busy,
                            ) {
                                Text("Choose photo")
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            query,
                            model::setQuery,
                            label = { Text("Title, author or ISBN") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                            enabled = !state.busy,
                        )
                        Button(
                            onClick = { model.search() },
                            enabled = !state.busy && query.isNotBlank(),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text("Find book")
                        }
                        if (username.isNotBlank())
                            TextButton(
                                onClick = {
                                    onHardcoverLookup(query)
                                },
                                enabled = !state.busy && query.isNotBlank(),
                            ) {
                                Text("Find directly on Hardcover")
                            }
                        Text(
                            "Text recognition stays on your phone. Searching sends the text to Open Library; linking searches Hardcover.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(candidates, key = { it.key }) { candidate ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    candidate.title,
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(candidate.authors.joinToString(", "))
                                TextButton(
                                    onClick = { selected = candidate },
                                    enabled = !state.busy,
                                ) {
                                    Text("Choose this book")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    selected?.let { candidate ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Save this book?") },
            text = {
                Text(
                    "${candidate.title}\n${candidate.authors.joinToString(", ")}\n\nYou can confirm its Hardcover match separately. Nothing is published yet."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.save(candidate, onSaved)
                        selected = null
                    }
                ) {
                    Text("Add to Want to Read")
                }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } },
        )
    }
    if (connect) HardcoverAccountDialog(model) { connect = false }
}
