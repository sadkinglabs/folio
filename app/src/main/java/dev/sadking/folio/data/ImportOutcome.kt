package dev.sadking.folio.data

import android.content.res.Resources
import dev.sadking.folio.R

data class ImportOutcome(
    val uri: String,
    val name: String,
    val result: String,
    val bookId: String?,
    val error: String?,
)

fun importFailure(error: Exception): String =
    when (error) {
        is StorageFull ->
            error.message ?: "Your phone is out of space. Free some space and try again."
        is SecurityException -> "File access expired. Choose this file again in the system picker."
        is java.io.FileNotFoundException ->
            "File is missing or unavailable. Download it locally and choose it again."
        is java.util.zip.ZipException -> "Not a readable EPUB ZIP archive. Download another copy."
        is IllegalArgumentException,
        is IllegalStateException -> error.message?.take(200) ?: "Invalid or unsupported EPUB"
        is java.io.IOException ->
            "Could not read or save this EPUB. Check storage and the file provider, then retry."
        else -> "Could not import this file. Choose it again or try another EPUB copy."
    }

/**
 * As [importFailure], with the plain messages from [res] for failures that have one: the phone out
 * of space wherever it happened.
 */
fun importFailure(error: Exception, res: Resources): String =
    if (isStorageFull(error)) res.getString(R.string.data6_storage_full) else importFailure(error)
