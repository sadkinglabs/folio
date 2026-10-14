package dev.sadking.folio.data

data class ImportOutcome(
    val uri: String,
    val name: String,
    val result: String,
    val bookId: String?,
    val error: String?,
)

fun importFailure(error: Exception): String =
    when (error) {
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
