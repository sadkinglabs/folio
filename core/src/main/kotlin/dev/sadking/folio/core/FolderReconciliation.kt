package dev.sadking.folio.core

/** Folder paths name files; hashes verify bytes. Neither is the library book identity. */
data class FolderFile(val path: String, val hash: String)

enum class FolderAction {
    TO_PHONE,
    TO_KINDLE,
    VERIFIED,
    CONFLICT,
}

data class FolderChange(val path: String, val action: FolderAction)

object FolderReconciliation {
    fun validatePath(path: String) {
        require(
            path.length in 1..4096 &&
                !path.startsWith('/') &&
                !path.contains('\\') &&
                path.none { it.code < 32 }
        )
        require(
            path.split('/').none {
                it.isEmpty() ||
                    it in listOf(".", "..") ||
                    it.startsWith('.') ||
                    it.endsWith(".sdr", true)
            }
        )
        require(path.endsWith(".epub", true))
    }

    /**
     * Additive reconciliation: changed content requires review; disappearance never means delete.
     */
    fun plan(phone: List<FolderFile>, kindle: List<FolderFile>): List<FolderChange> {
        fun index(files: List<FolderFile>): Map<String, String> {
            files.forEach {
                validatePath(it.path)
                require(it.hash.matches(Regex("[a-f0-9]{64}")))
            }
            require(files.map { it.path }.distinct().size == files.size) {
                "Ambiguous duplicate folder paths"
            }
            return files.associate { it.path to it.hash }
        }
        val p = index(phone)
        val k = index(kindle)
        return (p.keys + k.keys).sorted().map { path ->
            FolderChange(
                path,
                when {
                    p[path] == null -> FolderAction.TO_PHONE
                    k[path] == null -> FolderAction.TO_KINDLE
                    p[path] == k[path] -> FolderAction.VERIFIED
                    else -> FolderAction.CONFLICT
                },
            )
        }
    }
}
