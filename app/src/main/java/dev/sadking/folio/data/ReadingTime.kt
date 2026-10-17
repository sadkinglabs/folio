package dev.sadking.folio.data

import org.json.JSONArray

/**
 * One e-reader's reading time as Settings shows it. [name] is the roster's name for it, or null
 * when none is known (the screen then says "E-reader", or "E-reader before sync" when [old]). [old]
 * marks totals fetched over SSH by the first plugin, which never update.
 */
data class DeviceReadingTime(
    val key: String,
    val name: String?,
    val old: Boolean,
    val seconds: Long,
    val exportedAt: Long,
)

/**
 * KOReader's reading time from every e-reader: [devices] newest first, [days] summed across them
 * (`YYYY-MM-DD` to seconds, newest first). Read only: nothing here changes the stored rows.
 */
data class ReadingTime(val devices: List<DeviceReadingTime>, val days: List<Pair<String, Long>>) {
    val seconds: Long
        get() = devices.sumOf { it.seconds }

    /** When the newest totals were read on an e-reader (epoch seconds, its clock); 0 when none. */
    val updatedAt: Long
        get() = devices.maxOfOrNull { it.exportedAt } ?: 0L

    val isEmpty: Boolean
        get() = devices.isEmpty()

    companion object {
        val EMPTY = ReadingTime(emptyList(), emptyList())

        /**
         * The stored [rows] as the owner arranged the devices ([roster]). A row from sync is keyed
         * `link:<KOReader device id>`; any other row came from the first plugin over SSH, keyed by
         * the saved connection. Forgotten devices are left out. Devices the owner joined are one
         * e-reader reading one statistics database, so the newest totals win instead of adding up.
         * A row that can't be read is skipped, never shown as zero.
         */
        fun of(rows: List<DeviceStatistics>, roster: DeviceRoster): ReadingTime {
            val shown =
                rows
                    .map { row -> keyOf(row.deviceId) to row }
                    .filterNot { (key, _) -> roster.hidden(key) }
                    .groupBy { (key, _) -> roster.root(key) }
                    .map { (_, group) ->
                        group.maxWith(compareBy({ it.second.exportedAt }, { it.second.fetchedAt }))
                    }
                    .mapNotNull { (key, row) -> days(row)?.let { Triple(key, row, it) } }
                    .sortedByDescending { it.second.exportedAt }
            val days = HashMap<String, Long>()
            shown.forEach { (_, _, list) ->
                list.forEach { (date, seconds) -> days[date] = (days[date] ?: 0L) + seconds }
            }
            return ReadingTime(
                shown.map { (key, row, _) ->
                    DeviceReadingTime(
                        key,
                        roster.name(key),
                        DeviceKeys.isOld(key),
                        row.totalSeconds,
                        row.exportedAt,
                    )
                },
                days.toList().sortedByDescending { it.first },
            )
        }

        /** The roster key of a stored row. */
        fun keyOf(deviceId: String): String =
            if (deviceId.startsWith(LINK_DEVICE_PREFIX))
                DeviceKeys.ereader(deviceId.removePrefix(LINK_DEVICE_PREFIX))
            else DeviceKeys.ssh(deviceId)

        private fun days(row: DeviceStatistics): List<Pair<String, Long>>? =
            runCatching {
                    val array = JSONArray(row.days)
                    (0 until array.length()).map {
                        val day = array.getJSONObject(it)
                        day.getString("date") to day.getLong("seconds")
                    }
                }
                .getOrNull()
    }
}
