package dev.sadking.folio

import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.sadking.folio.data.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiscoveryCommitTest {
    @Test
    fun callbackCancellationCannotDeleteCommittedArtwork() =
        runBlocking<Unit> {
            val app = ApplicationProvider.getApplicationContext<FolioApplication>()
            val model = DiscoveryViewModel(app)
            val store = ViewModelStore().apply { put("discovery", model) }
            val title = "Commit test ${UUID.randomUUID()}"
            val photo = File(app.cacheDir, "commit-test.png")
            Bitmap.createBitmap(24, 32, Bitmap.Config.ARGB_8888).also { image ->
                photo.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            model.cover.value = photo.absolutePath
            try {
                model
                    .save(
                        MetadataCandidate("/works/OL999991W", title, listOf("Fixture"), null, null)
                    ) {
                        throw CancellationException("Fixture closes the view after commit")
                    }
                    .join()
                val entry = app.database.library().allWishes().first { it.title == title }
                assertTrue(File(entry.coverPath!!).isFile)
                assertFalse(model.state.value.busy)
                app.repository.removeLocal(setOf(entry.id))
                app.repository.purgeRemoved(entry.id)
            } finally {
                store.clear()
                photo.delete()
            }
        }
}
