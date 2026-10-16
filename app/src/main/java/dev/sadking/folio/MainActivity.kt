package dev.sadking.folio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import dev.sadking.folio.ui.FolioApp

class MainActivity : ComponentActivity() {
    private lateinit var model: LibraryViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[LibraryViewModel::class.java]
        if (savedInstanceState == null) consume(intent)
        setContent { FolioApp(model) }
        // Folio Link stays on across launches if the user left it on.
        (application as FolioApplication).link.resumeIfEnabled()
    }

    override fun onStart() {
        super.onStart()
        // Each time Librillo comes to the foreground: file the Inbox and catch up with the folder.
        model.refreshFolder()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consume(intent)
    }

    @Suppress("DEPRECATION")
    private fun consume(intent: Intent) {
        val uris =
            when (intent.action) {
                    Intent.ACTION_SEND ->
                        listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                    Intent.ACTION_SEND_MULTIPLE ->
                        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                    Intent.ACTION_VIEW -> listOfNotNull(intent.data)
                    else -> emptyList()
                }
                .filter { it.scheme == "content" }
                .distinct()
        if (uris.isNotEmpty()) model.import(uris)
    }
}
