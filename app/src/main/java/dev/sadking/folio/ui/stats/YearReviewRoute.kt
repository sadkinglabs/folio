package dev.sadking.folio.ui.stats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.data.Book
import dev.sadking.folio.ui.LocalAppMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The year in review for [year], and its share image. [review] is null while it is made. The card
 * is drawn out of sight ([ShareCardRecorder]) and turned into a PNG on Share; the image holds
 * titles and covers only. [shareNow] starts a share once the review is ready, then calls
 * [onShareStarted].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun YearReviewRoute(
    review: YearReview?,
    year: Int,
    shareNow: Boolean,
    onShareStarted: () -> Unit,
    onBack: () -> Unit,
    onOpenBook: (Book) -> Unit,
) {
    if (review == null) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_year_review_title, year)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            stringResource(R.string.shell_back),
                        )
                    }
                },
            )
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator(Modifier.size(48.dp))
            }
        }
        return
    }
    val layer = rememberGraphicsLayer()
    val context = LocalContext.current
    val host = LocalAppMessages.current?.host
    val scope = rememberCoroutineScope()
    val preparing = stringResource(R.string.stats_share_preparing)
    val failed = stringResource(R.string.stats_share_failed)
    var sharing by remember { mutableStateOf(false) }

    fun share() {
        if (sharing) return
        sharing = true
        scope.launch {
            val note = launch {
                host?.showSnackbar(preparing, duration = SnackbarDuration.Indefinite)
            }
            var ok = false
            try {
                // Let the hidden card draw, and its covers load, before it is read.
                withFrameNanos {}
                withFrameNanos {}
                val bitmap = layer.toImageBitmap().asAndroidBitmap()
                val file =
                    withContext(Dispatchers.IO) {
                        writeSharePng(bitmap, context.cacheDir, review.year)
                    }
                context.startActivity(shareImageIntent(context, file))
                ok = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Never logged: the message is enough, and nothing about the books is kept.
            } finally {
                note.cancel()
                sharing = false
            }
            if (!ok) host?.showSnackbar(failed)
        }
    }

    LaunchedEffect(shareNow) {
        if (shareNow) {
            share()
            onShareStarted()
        }
    }

    Box(Modifier.fillMaxSize()) {
        YearReviewScreen(
            review,
            onBack = onBack,
            onShare = ::share,
            onBook = onOpenBook,
            sharing = sharing,
        )
        ShareCardRecorder(review, layer)
    }
}
