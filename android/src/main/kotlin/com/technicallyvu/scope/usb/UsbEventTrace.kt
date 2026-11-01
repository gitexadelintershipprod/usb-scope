package com.technicallyvu.scope.usb

import java.util.Locale

/** A read-only observation; null permission means Android could not report its current value. */
data class UsbObservedAttachment(val identity: UsbPermissionDevice, val granted: Boolean?)

/**
 * Bounded, process-local diagnostic history. Raw Android USB identities exist only as in-memory
 * comparison keys: exported entries use anonymous attachment generations and non-unique VID:PID.
 * No serial number, user wall-clock time, file, media or network access is involved.
 */
class UsbEventLog(
    private val maxEntries: Int = 64,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Attachment(val label: String, var granted: Boolean?)
    private data class Event(val elapsedMillis: Long, val stage: String, val detail: String)

    private val startedAt = clockMillis()
    private val events = ArrayDeque<Event>()
    private val attachments = LinkedHashMap<UsbPermissionDevice, Attachment>()
    private val lastStates = LinkedHashMap<String, String>()
    private var nextAttachment = 1L
    private var lastObservedLimit = false

    init { require(maxEntries in 1..MAX_ENTRIES) }

    /**
     * Compare successful enumerations only. An enumeration error must not be passed as an empty
     * list, since that would manufacture a detach. A reused identity after an observed absence
     * receives a new label, making re-enumeration distinguishable from a rejected permission.
     */
    @Synchronized
    fun observeAttachments(observed: List<UsbObservedAttachment>) {
        val limited = observed.distinctBy { it.identity }.take(MAX_ATTACHMENTS)
        val current = limited.mapTo(HashSet()) { it.identity }
        val removed = attachments.keys.filter { it !in current }
        for (identity in removed) {
            val previous = attachments.remove(identity) ?: continue
            append("detach", previous.label)
        }
        for ((identity, granted) in limited) {
            val previous = attachments[identity]
            if (previous == null) {
                val attached = Attachment("A${nextAttachment++} ${deviceCode(identity)}", granted)
                attachments[identity] = attached
                append("attach", "${attached.label}; USB permission=${permission(granted)}")
            } else if (previous.granted != granted) {
                val old = permission(previous.granted)
                previous.granted = granted
                append("permission", "${previous.label}; USB permission=$old -> ${permission(granted)}")
            }
        }
        val overLimit = observed.size > MAX_ATTACHMENTS
        if (overLimit && !lastObservedLimit) append("inventory", "Attachment trace limited to $MAX_ATTACHMENTS devices")
        lastObservedLimit = overLimit
    }

    /** A real system detach remains evidence even when unplug/replug occurs between polls. */
    @Synchronized
    fun onDetached(identity: UsbPermissionDevice) {
        val previous = attachments.remove(identity)
        append("detach broadcast", previous?.label ?: "unobserved ${deviceCode(identity)}")
    }

    /** A stale/unobserved callback is identified explicitly instead of creating a false attach. */
    @Synchronized
    fun label(identity: UsbPermissionDevice): String =
        attachments[identity]?.label ?: "unobserved ${deviceCode(identity)}"

    /** Explicit events retain repeated user attempts; only identical adjacent entries coalesce. */
    @Synchronized
    fun record(stage: String, detail: String = "") = append(stage, detail)

    @Synchronized
    fun recordDevice(stage: String, identity: UsbPermissionDevice, detail: String = "") =
        append(stage, join(label(identity), detail))

    /** Unchanged periodic checks coalesce even when another stage has emitted an event. */
    @Synchronized
    fun recordState(stage: String, detail: String) {
        val safeStage = sanitize(stage, MAX_STAGE_LENGTH)
        val safeDetail = sanitize(detail, MAX_DETAIL_LENGTH)
        if (lastStates[safeStage] == safeDetail) return
        lastStates.remove(safeStage)
        lastStates[safeStage] = safeDetail
        while (lastStates.size > MAX_STATES) lastStates.remove(lastStates.keys.first())
        append(safeStage, safeDetail)
    }

    @Synchronized
    fun recordDeviceState(stage: String, identity: UsbPermissionDevice, detail: String) =
        recordState("$stage ${label(identity)}", detail)

    @Synchronized
    fun exportText(): String = buildString {
        appendLine("USB event history (local process memory; relative time; newest $maxEntries events)")
        if (events.isEmpty()) append("No events recorded.")
        else events.forEach { event ->
            append("+").append(event.elapsedMillis).append("ms ").append(event.stage)
            if (event.detail.isNotEmpty()) append(": ").append(event.detail)
            appendLine()
        }
    }.trimEnd()

    private fun append(stage: String, detail: String) {
        val safeStage = sanitize(stage, MAX_STAGE_LENGTH)
        val safeDetail = sanitize(detail, MAX_DETAIL_LENGTH)
        if (events.lastOrNull()?.let { it.stage == safeStage && it.detail == safeDetail } == true) return
        events.addLast(Event((clockMillis() - startedAt).coerceAtLeast(0), safeStage, safeDetail))
        while (events.size > maxEntries) events.removeFirst()
    }

    private fun join(label: String, detail: String): String = if (detail.isEmpty()) label else "$label; $detail"
    private fun permission(value: Boolean?): String = value?.toString() ?: "unknown"
    private fun deviceCode(identity: UsbPermissionDevice): String =
        String.format(Locale.ROOT, "%04X:%04X", identity.vendorId and 0xffff, identity.productId and 0xffff)

    private fun sanitize(value: String, maximum: Int): String = value
        .replace(USB_PATH, "[USB device]")
        .replace(LINE_BREAKS, " ")
        .take(maximum)

    companion object {
        private const val MAX_ENTRIES = 256
        private const val MAX_ATTACHMENTS = 128
        private const val MAX_STATES = 128
        private const val MAX_STAGE_LENGTH = 64
        private const val MAX_DETAIL_LENGTH = 256
        private val USB_PATH = Regex("/dev/bus/usb/[^\\s,;\\)]+")
        private val LINE_BREAKS = Regex("[\\r\\n\\t\\u0000-\\u001f\\u007f]+")
    }
}

/** Shared trace for activity callbacks, enumeration and USB-open stages; never persisted. */
object UsbEventTrace {
    private val log = UsbEventLog()
    fun observeAttachments(observed: List<UsbObservedAttachment>) = log.observeAttachments(observed)
    fun onDetached(identity: UsbPermissionDevice) = log.onDetached(identity)
    fun label(identity: UsbPermissionDevice): String = log.label(identity)
    fun record(stage: String, detail: String = "") = log.record(stage, detail)
    fun recordDevice(stage: String, identity: UsbPermissionDevice, detail: String = "") = log.recordDevice(stage, identity, detail)
    fun recordState(stage: String, detail: String) = log.recordState(stage, detail)
    fun recordDeviceState(stage: String, identity: UsbPermissionDevice, detail: String) = log.recordDeviceState(stage, identity, detail)
    fun exportText(): String = log.exportText()
}
