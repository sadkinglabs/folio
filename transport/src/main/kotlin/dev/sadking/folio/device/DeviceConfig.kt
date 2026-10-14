package dev.sadking.folio.device

data class DeviceConfig(
    val host: String = "",
    val port: Int = 2222,
    val username: String = "root",
    val directory: String = "/mnt/us/documents",
    val fingerprint: String = "",
    val authentication: String = "Key",
) {
    val id: String
        get() = "$fingerprint:$username:$directory"

    val legacyId: String
        get() = "$host:$port:$username:$directory:$fingerprint:$authentication"

    fun validate() {
        validateEndpoint(host, port)
        require(authentication in listOf("Key", "Password", "KOReader password-free")) {
            "Unsupported authentication mode"
        }
        require(username.isNotBlank()) { "Username is required" }
        require(
            directory.startsWith('/') &&
                directory.length > 1 &&
                directory.split('/').none { it == ".." || it == "." } &&
                !directory.contains('\\')
        ) {
            "Use an absolute Kindle folder without traversal"
        }
        require(fingerprint.matches(Regex("SHA256:[A-Za-z0-9+/]{43}=?"))) {
            "Enter the verified SSH SHA256 fingerprint"
        }
    }
}

data class DeviceCredential(val secret: String, val privateKey: ByteArray? = null)
