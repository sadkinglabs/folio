package dev.sadking.folio.data

import dev.sadking.folio.sync.EreaderSync

/**
 * The keys devices are known by, one namespace per source, so a name, a merge or a forget can point
 * at any of them.
 */
object DeviceKeys {
    /**
     * A KOReader device by its device id. Sync and Progress sync (kosync) both send KOReader's
     * `device_id`, so an e-reader that does both has one key.
     */
    fun ereader(id: String) = "ereader:$id"

    /**
     * The saved SSH connection whose old bridge read positions before sync (the `reading` table's
     * deviceId). Nothing writes these any more: they can never update.
     */
    fun ssh(id: String) = "ssh:$id"

    fun isOld(key: String) = key.startsWith("ssh:")
}

/**
 * What the data says about one device key: the name it sends ("" when none), when it was last heard
 * from (phone time, in milliseconds), whether it syncs books, and how many books it has a position
 * for. [old] marks the positions the SSH bridge read before sync, which never update.
 */
data class DeviceSighting(
    val key: String,
    val name: String,
    val lastSeen: Long,
    val syncs: Boolean = false,
    val positions: Int = 0,
    val old: Boolean = false,
)

/**
 * Every device the phone has heard from: e-readers that sync ([ereaders]), Progress sync reports
 * ([reports], the phone's own ones, from [phoneDeviceId], left out) and the old SSH positions
 * ([snapshots]). Reports and syncs from one KOReader device id come together under one key. Pure.
 */
fun deviceSightings(
    ereaders: Collection<EreaderSync>,
    reports: List<KosyncReport>,
    snapshots: List<ReadingSnapshot>,
    phoneDeviceId: String = "",
): List<DeviceSighting> {
    val live =
        (ereaders.map { it.id } + reports.map { it.deviceId })
            .filter { it.isNotBlank() && it != phoneDeviceId }
            .distinct()
            .map { id ->
                val sync = ereaders.firstOrNull { it.id == id }
                val sent = reports.filter { it.deviceId == id }
                DeviceSighting(
                    DeviceKeys.ereader(id),
                    sync?.name?.takeIf { it.isNotBlank() }
                        ?: sent.maxByOrNull { it.receivedAt }?.device?.trim().orEmpty(),
                    maxOf(
                        sync?.let { maxOf(it.lastSync, it.syncingSince) } ?: 0L,
                        sent.maxOfOrNull { it.receivedAt } ?: 0L,
                    ),
                    syncs = sync != null,
                    positions = sent.map { it.document }.distinct().size,
                )
            }
    val old =
        snapshots
            .groupBy { it.deviceId }
            .map { (id, rows) ->
                DeviceSighting(
                    DeviceKeys.ssh(id),
                    "",
                    rows.maxOf { it.fetchedAt },
                    positions = rows.map { it.bookId }.distinct().size,
                    old = true,
                )
            }
    return live + old
}

/**
 * One device as Librillo shows it: the devices the owner said are the same one, under one name.
 * [key] is the one the others were merged into. [name] is the owner's name for it, else the name it
 * sends ([ownName]), else "" (the screens then say "E-reader", or "Old e-reader position (before
 * sync)" when [old]). [hidden]: the owner forgot it and it has not reported since.
 */
data class KnownDevice(
    val key: String,
    val name: String,
    val ownName: String,
    val renamed: Boolean,
    val members: List<DeviceSighting>,
    val hidden: Boolean,
) {
    val lastSeen: Long
        get() = members.maxOfOrNull { it.lastSeen } ?: 0L

    val syncs: Boolean
        get() = members.any { it.syncs }

    val positions: Int
        get() = members.sumOf { it.positions }

    /** Only old SSH positions, which never update. */
    val old: Boolean
        get() = members.isNotEmpty() && members.all { it.old }

    /** The devices merged into this one (every member but its own key). */
    val merged: List<DeviceSighting>
        get() = members.filter { it.key != key }
}

/**
 * Every known device, after the owner's names, merges and forgets ([profiles]). Pure: it never
 * changes the reports, snapshots or positions themselves, only how they are shown.
 */
class DeviceRoster(sightings: List<DeviceSighting>, profiles: List<DeviceProfile>) {
    private val byKey = profiles.associateBy { it.deviceKey }

    /** The key [key] is shown under: its merge target's, followed to the end, cycles cut. */
    fun root(key: String): String {
        var current = key
        val seen = mutableSetOf(current)
        while (true) {
            val next = byKey[current]?.mergedInto ?: return current
            if (!seen.add(next)) return current
            current = next
        }
    }

    /** Every device, newest first; forgotten ones included, marked [KnownDevice.hidden]. */
    val all: List<KnownDevice> =
        sightings
            .groupBy { root(it.key) }
            .map { (key, members) ->
                val profile = byKey[key]
                val custom = profile?.displayName?.trim().orEmpty()
                val sent =
                    members.firstOrNull { it.key == key }?.name?.takeIf { it.isNotBlank() }
                        ?: members
                            .filter { it.syncs }
                            .firstNotNullOfOrNull {
                                it.name.takeIf { n -> n.isNotBlank() }
                            }
                        ?: members
                            .sortedByDescending { it.lastSeen }
                            .firstNotNullOfOrNull {
                                it.name.takeIf { n -> n.isNotBlank() }
                            }
                        ?: ""
                val lastSeen = members.maxOf { it.lastSeen }
                val forgotten = profile?.hiddenAt ?: 0L
                KnownDevice(
                    key,
                    custom.ifEmpty { sent },
                    sent,
                    custom.isNotEmpty(),
                    members.sortedByDescending { it.lastSeen },
                    hidden = forgotten > 0 && lastSeen <= forgotten,
                )
            }
            .sortedByDescending { it.lastSeen }

    private val byMember = all.flatMap { d -> d.members.map { it.key to d } }.toMap()

    /** The devices to show: every one the owner has not forgotten. */
    val shown: List<KnownDevice>
        get() = all.filterNot { it.hidden }

    /** The device [key] belongs to, or null when the phone has never heard from it. */
    fun device(key: String): KnownDevice? = byMember[key] ?: all.firstOrNull { it.key == root(key) }

    /** The name to show for [key]: the owner's, else the device's own; null when none is known. */
    fun name(key: String): String? = device(key)?.name?.takeIf { it.isNotBlank() }

    /** True when the owner forgot [key]'s device and it has not reported since. */
    fun hidden(key: String): Boolean = device(key)?.hidden == true

    /**
     * Progress sync reports as one row per device: forgotten devices left out, merged ones folded
     * together (the newest report wins), each named as Librillo shows it.
     */
    fun reports(reports: List<KosyncReport>): List<KosyncReport> =
        reports
            .filterNot { hidden(DeviceKeys.ereader(it.deviceId)) }
            .groupBy { root(DeviceKeys.ereader(it.deviceId)) }
            .map { (_, rows) ->
                val newest = rows.maxWith(compareBy({ it.timestamp }, { it.receivedAt }))
                newest.copy(device = name(DeviceKeys.ereader(newest.deviceId)) ?: newest.device)
            }
            .sortedByDescending { it.timestamp }

    /**
     * The e-readers that sync, as the Device tab shows them: named as Librillo shows them, one per
     * device (the one that synced last), forgotten ones left out.
     */
    fun syncCards(ereaders: List<EreaderSync>): List<EreaderSync> =
        ereaders
            .filterNot { hidden(DeviceKeys.ereader(it.id)) }
            .groupBy { root(DeviceKeys.ereader(it.id)) }
            .map { (_, group) -> group.maxBy { maxOf(it.lastSync, it.syncingSince) } }
            .map(::named)
            .sortedByDescending { maxOf(it.lastSync, it.syncingSince) }

    /** [ereader] with the name Librillo shows for it. */
    fun named(ereader: EreaderSync): EreaderSync =
        name(DeviceKeys.ereader(ereader.id))?.let { ereader.copy(name = it) } ?: ereader

    companion object {
        val EMPTY = DeviceRoster(emptyList(), emptyList())

        /** The longest name the owner can give a device. */
        const val MAX_NAME = 80
    }
}

/**
 * The owner's changes to a device, each as the one profile row to save, from the row there is now
 * ([current], null when none). Pure, so the rules are tested without a database. None of them
 * touches a report, a snapshot or a position.
 */
object DeviceProfiles {
    /** [device] is called [name] everywhere; a blank name goes back to the device's own. */
    fun rename(device: KnownDevice, current: DeviceProfile?, name: String, now: Long) =
        (current ?: DeviceProfile(device.key, updatedAt = now)).copy(
            displayName = name.trim().replace(Regex("\\s+"), " ").take(DeviceRoster.MAX_NAME),
            updatedAt = now,
        )

    /**
     * "This is the same e-reader as [into]": [device] and everything merged into it show as
     * [into]'s device from now on. Null when that is the same device already.
     */
    fun merge(
        directory: DeviceRoster,
        device: KnownDevice,
        current: DeviceProfile?,
        into: String,
        now: Long,
    ): DeviceProfile? {
        val target = directory.root(into)
        if (target == device.key) return null
        return (current ?: DeviceProfile(device.key, updatedAt = now)).copy(
            mergedInto = target,
            updatedAt = now,
        )
    }

    /** "Show separately": [memberKey] (and what was merged into it) is its own device again. */
    fun separate(memberKey: String, current: DeviceProfile?, now: Long) =
        (current ?: DeviceProfile(memberKey, updatedAt = now)).copy(
            mergedInto = null,
            updatedAt = now,
        )

    /**
     * "Forget this device": hidden from Progress and the Device tab until it is heard from again,
     * after now (or after it was last seen, if the phone's clock was behind).
     */
    fun forget(device: KnownDevice, current: DeviceProfile?, now: Long) =
        (current ?: DeviceProfile(device.key, updatedAt = now)).copy(
            hiddenAt = maxOf(now, device.lastSeen),
            updatedAt = now,
        )
}
