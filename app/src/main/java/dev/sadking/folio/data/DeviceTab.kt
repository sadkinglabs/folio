package dev.sadking.folio.data

/** Folio Link as the Device tab shows it: one word, one colour. */
enum class LinkStatus {
    OFF,
    /** Switched on, the server not yet bound. */
    STARTING,
    /** Listening, but the phone has no local address an e-reader could reach. */
    WAITING_FOR_NETWORK,
    ON,
}

fun linkStatus(enabled: Boolean, port: Int?, addresses: List<String>): LinkStatus =
    when {
        !enabled -> LinkStatus.OFF
        port == null -> LinkStatus.STARTING
        addresses.isEmpty() -> LinkStatus.WAITING_FOR_NETWORK
        else -> LinkStatus.ON
    }

/** Where an e-reader last was in a book it reported through Folio Link. */
data class EreaderPosition(val book: Book, val percent: Double)

/**
 * An e-reader that has reached this phone through Folio Link. [name] is what KOReader called
 * itself, or empty when only the Folio plugin has spoken (it sends an id, not a name). [lastSeen]
 * is the phone's clock, in milliseconds.
 */
data class EreaderSeen(
    val id: String,
    val name: String,
    val lastSeen: Long,
    val position: EreaderPosition?,
)

/**
 * The e-readers seen, newest first. Position reports carry the device's name and a KOReader
 * document digest, which [digestOf] maps to a book (null when the file cannot be read; digests are
 * only computed when a report could match). Confirmed downloads are keyed `link:<device>` and give
 * a device without a name. The phone's own echoes ([phoneDeviceId]) are not an e-reader.
 */
fun ereadersSeen(
    reports: List<KosyncReport>,
    transfers: List<Transfer>,
    books: List<Book>,
    phoneDeviceId: String?,
    digestOf: (Book) -> String?,
): List<EreaderSeen> {
    val fromDevices = reports.filter { phoneDeviceId == null || it.deviceId != phoneDeviceId }
    val byDocument =
        if (fromDevices.isEmpty()) emptyMap()
        else {
            val documents = fromDevices.map { it.document }.toSet()
            books
                .mapNotNull { book ->
                    digestOf(book)?.takeIf { it in documents }?.let { it to book }
                }
                .toMap()
        }
    val seen = mutableMapOf<String, EreaderSeen>()
    fromDevices
        .groupBy { it.deviceId }
        .forEach { (id, group) ->
            val placed =
                group
                    .filter { it.document in byDocument }
                    .maxWithOrNull(compareBy({ it.timestamp }, { it.receivedAt }))
            seen[id] =
                EreaderSeen(
                    id,
                    group.maxBy { it.receivedAt }.device.trim(),
                    group.maxOf { it.receivedAt },
                    placed?.let {
                        EreaderPosition(byDocument.getValue(it.document), it.percentage)
                    },
                )
        }
    transfers
        .filter { it.deviceId.startsWith(LINK_DEVICE_PREFIX) }
        .groupBy { it.deviceId.removePrefix(LINK_DEVICE_PREFIX) }
        .forEach { (id, group) ->
            val at = group.maxOf { it.completedAt }
            val known = seen[id]
            seen[id] =
                known?.copy(lastSeen = maxOf(known.lastSeen, at)) ?: EreaderSeen(id, "", at, null)
        }
    return seen.values.sortedByDescending { it.lastSeen }
}

/** A book waiting for Folio Link e-readers. [sent] once any e-reader confirmed this revision. */
data class QueuedBook(val book: Book, val sent: Boolean)

/** The send queue in the order books were added; books no longer in the library are skipped. */
fun queuedBooks(ids: List<String>, books: List<Book>, transfers: List<Transfer>): List<QueuedBook> {
    val byId = books.associateBy { it.id }
    val delivered =
        transfers
            .filter { it.deviceId.startsWith(LINK_DEVICE_PREFIX) }
            .groupBy({ it.bookId }, { it.hash })
    return ids.mapNotNull { id ->
        byId[id]?.let { QueuedBook(it, delivered[id]?.contains(it.currentHash) == true) }
    }
}

/** Transfers to Folio Link e-readers are keyed apart from SSH devices. */
const val LINK_DEVICE_PREFIX = "link:"
