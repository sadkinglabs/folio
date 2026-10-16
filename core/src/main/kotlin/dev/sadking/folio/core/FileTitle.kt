package dev.sadking.folio.core

/** A title made from a file name, for books whose file says nothing about themselves. */
object FileTitle {
    private val bookExtensions = setOf("epub", "pdf", "cbz", "cbr", "zip", "rar")

    fun from(fileName: String?): String {
        val name = fileName.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val extension = name.substringAfterLast('.', "").lowercase()
        val base = if (extension in bookExtensions) name.dropLast(extension.length + 1) else name
        return base.replace('_', ' ').replace(Regex("""\s+"""), " ").trim().ifEmpty { "Untitled" }
    }
}
