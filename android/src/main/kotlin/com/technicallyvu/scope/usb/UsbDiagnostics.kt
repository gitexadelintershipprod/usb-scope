package com.technicallyvu.scope.usb

import android.Manifest
import android.app.AppOpsManager
import android.app.ActivityManager
import android.hardware.SensorPrivacyManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.technicallyvu.scope.BuildConfig
import com.technicallyvu.scope.core.driver.DriverRegistry
import java.util.Locale

/** Current attachment objects are returned only for the activity's explicit permission buttons. */
data class UsbDiagnosticsResult(val report: String, val devices: List<UsbDevice>)

/** Read-only descriptor data; deliberately excludes USB paths, serials and product-name strings. */
data class UsbDiagnosticEndpoint(
    val address: Int,
    val type: Int,
    val direction: Int,
    val maxPacketSize: Int,
    val interval: Int,
)

data class UsbDiagnosticInterface(
    val number: Int,
    val alternateSetting: Int,
    val usbClass: Int,
    val subclass: Int,
    val protocol: Int,
    val endpoints: List<UsbDiagnosticEndpoint> = emptyList(),
)

data class UsbDiagnosticDevice(
    val vendorId: Int,
    val productId: Int,
    val usbClass: Int,
    val subclass: Int,
    val protocol: Int,
    val usbPermissionGranted: Boolean?,
    val cameraPermissionRequired: Boolean?,
    val matchingDriverIds: List<String>,
    val interfaces: List<UsbDiagnosticInterface>,
    val inspectionErrors: List<String> = emptyList(),
    val attachmentLabel: String? = null,
)

data class UsbDiagnosticSnapshot(
    val appVersion: String,
    val appVersionCode: Int,
    val appBuild: String,
    val sdk: Int,
    val androidRelease: String,
    val manufacturer: String,
    val model: String,
    val osBuild: String,
    val usbHostSupported: Boolean,
    val cameraGranted: Boolean,
    val devices: List<UsbDiagnosticDevice>,
    val enumerationError: String? = null,
    val isochronousSupported: Boolean = false,
    val appPackage: String = "",
    val appUid: Int = -1,
    val cameraAppOp: String = "not queried",
    val cameraPrivacyCapability: String = "not queried",
    val cameraAppOpRaw: String = "not queried",
    val processImportance: String = "not queried",
)

/** No USB open, control/bulk transfers, permission requests, serial reads, files or network calls. */
object UsbDiagnostics {
    fun collect(context: Context): UsbDiagnosticsResult {
        val usb = context.getSystemService(UsbManager::class.java)
        val enumeration = runCatching {
            checkNotNull(usb) { "USB service unavailable" }
            usb.deviceList.values.sortedWith(
                compareBy<UsbDevice> { it.vendorId }.thenBy { it.productId }.thenBy { it.deviceId },
            )
        }
        val devices = enumeration.getOrDefault(emptyList())
        val descriptions = devices.map { device ->
            val interfaces = runCatching {
                // Include every alternate setting: isochronous UVC endpoints commonly live in
                // alt 1 or later, while alt 0 has no endpoints at all.
                (0 until device.interfaceCount).map { index ->
                    val iface = device.getInterface(index)
                    UsbDiagnosticInterface(
                        iface.id, iface.alternateSetting, iface.interfaceClass, iface.interfaceSubclass,
                        iface.interfaceProtocol,
                        (0 until iface.endpointCount).map { endpointIndex ->
                            val endpoint = iface.getEndpoint(endpointIndex)
                            UsbDiagnosticEndpoint(
                                endpoint.address, endpoint.type, endpoint.direction,
                                endpoint.maxPacketSize, endpoint.interval,
                            )
                        },
                    )
                }
            }
            val permission = runCatching { checkNotNull(usb).hasPermission(device) }
            val matches = runCatching {
                // Match exactly the same alt-0 summary used by the live session. Do not open the
                // device to fetch raw descriptors or probe a format just for a diagnostic report.
                val info = AndroidDeviceSource.info(device)
                DriverRegistry.all.filter { it.matches(info) }.map { it.id }
            }
            UsbDiagnosticDevice(
                device.vendorId, device.productId, device.deviceClass, device.deviceSubclass,
                device.deviceProtocol, permission.getOrNull(),
                interfaces.getOrNull()?.let { all -> requiresCameraPermission(device.deviceClass, all.map { it.usbClass }) },
                matches.getOrDefault(emptyList()), interfaces.getOrDefault(emptyList()),
                buildList {
                    interfaces.exceptionOrNull()?.let { add("Interfaces: ${it.javaClass.simpleName}") }
                    permission.exceptionOrNull()?.let { add("USB permission: ${it.javaClass.simpleName}") }
                    matches.exceptionOrNull()?.let { add("Driver matching: ${it.javaClass.simpleName}") }
                },
                attachmentLabel = UsbEventTrace.label(AndroidDeviceSource.identity(device)),
            )
        }
        val snapshot = UsbDiagnosticSnapshot(
            appVersion = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            appBuild = BuildConfig.GIT_SHA,
            sdk = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            osBuild = Build.ID,
            usbHostSupported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
            cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
            devices = descriptions,
            enumerationError = enumeration.exceptionOrNull()?.javaClass?.simpleName,
            isochronousSupported = NativeIsochronous.available,
            appPackage = context.packageName,
            appUid = android.os.Process.myUid(),
            cameraAppOp = cameraAppOp(context),
            cameraPrivacyCapability = cameraPrivacyCapability(context),
            cameraAppOpRaw = cameraAppOp(context, raw = true),
            processImportance = runCatching {
                val state = ActivityManager.RunningAppProcessInfo()
                ActivityManager.getMyMemoryState(state)
                "${state.importance} (foreground=${state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND}); diagnostic only"
            }.getOrElse { "unavailable (${it.javaClass.simpleName})" },
        )
        return UsbDiagnosticsResult(formatUsbDiagnostics(snapshot) + "\n\n" + UsbEventTrace.exportText(), devices)
    }

    private fun cameraAppOp(context: Context, raw: Boolean = false): String = runCatching {
        val manager = context.getSystemService(AppOpsManager::class.java) ?: return@runCatching "service unavailable"
        val mode = if (Build.VERSION.SDK_INT >= 36) {
            if (raw) manager.checkOpRawNoThrow(AppOpsManager.OPSTR_CAMERA, android.os.Process.myUid(), context.packageName, null)
            else manager.checkOpNoThrow(AppOpsManager.OPSTR_CAMERA, android.os.Process.myUid(), context.packageName, null)
        } else {
            @Suppress("DEPRECATION")
            if (raw) manager.unsafeCheckOpRawNoThrow(AppOpsManager.OPSTR_CAMERA, android.os.Process.myUid(), context.packageName)
            else manager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_CAMERA, android.os.Process.myUid(), context.packageName)
        }
        val name = when (mode) {
            AppOpsManager.MODE_ALLOWED -> "allowed"
            AppOpsManager.MODE_IGNORED -> "ignored"
            AppOpsManager.MODE_ERRORED -> "errored"
            AppOpsManager.MODE_DEFAULT -> "default"
            AppOpsManager.MODE_FOREGROUND -> "foreground"
            else -> "unknown"
        }
        "$name ($mode); diagnostic only"
    }.getOrElse { "unavailable (${it.javaClass.simpleName})" }

    private fun cameraPrivacyCapability(context: Context): String {
        if (Build.VERSION.SDK_INT < 31) return "not exposed on this Android version"
        return runCatching {
            val manager = context.getSystemService(SensorPrivacyManager::class.java)
                ?: return@runCatching "service unavailable"
            val supported = manager.supportsSensorToggle(SensorPrivacyManager.Sensors.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) {
                val software = manager.supportsSensorToggle(SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE, SensorPrivacyManager.Sensors.CAMERA)
                val hardware = manager.supportsSensorToggle(SensorPrivacyManager.TOGGLE_TYPE_HARDWARE, SensorPrivacyManager.Sensors.CAMERA)
                "supported=$supported; software=$software; hardware=$hardware"
            } else "supported=$supported"
        }.getOrElse { "unavailable (${it.javaClass.simpleName})" }
    }
}

/** Pure formatting helpers also work in local JVM tests without Android services or USB hardware. */
fun usbClassSummary(value: Int): String {
    val symbolic = when (value) {
        0x00 -> "PER_INTERFACE"
        0x01 -> "AUDIO"
        0x02 -> "COMMUNICATIONS"
        0x03 -> "HID"
        0x06 -> "STILL_IMAGE"
        0x08 -> "MASS_STORAGE"
        0x09 -> "HUB"
        0x0a -> "CDC_DATA"
        0x0e -> "VIDEO"
        0xef -> "MISCELLANEOUS"
        0xfe -> "APPLICATION_SPECIFIC"
        0xff -> "VENDOR_SPECIFIC"
        else -> "OTHER"
    }
    return "0x${hex(value, 2)} ($symbolic)"
}

fun usbEndpointSummary(endpoint: UsbDiagnosticEndpoint): String {
    val transfer = when (endpoint.type) {
        0 -> "CONTROL"
        1 -> "ISOCHRONOUS"
        2 -> "BULK"
        3 -> "INTERRUPT"
        else -> "UNKNOWN"
    }
    val direction = when (endpoint.direction) {
        0x80 -> "IN"
        0x00 -> "OUT"
        else -> "UNKNOWN"
    }
    return "EP 0x${hex(endpoint.address, 2)}: type=$transfer(${endpoint.type}), " +
        "direction=$direction(0x${hex(endpoint.direction, 2)}), " +
        "maxPacketSize=${endpoint.maxPacketSize}, interval=${endpoint.interval}"
}

fun usbVideoTransferSummary(interfaces: List<UsbDiagnosticInterface>, isochronousSupported: Boolean = false): String {
    val video = interfaces.filter { it.usbClass == 0x0e && it.subclass == 0x02 }
    val candidates = if (video.isNotEmpty()) video else interfaces
    val input = candidates.flatMap { it.endpoints }.filter { it.direction == 0x80 }
    val bulk = input.count { it.type == 2 }
    val isochronous = input.count { it.type == 1 }
    return when {
        video.isNotEmpty() && bulk > 0 ->
            "VideoStreaming: bulk IN=$bulk, isochronous IN=$isochronous; bulk candidate, format/handshake not tested."
        video.isNotEmpty() && isochronous > 0 ->
            if (isochronousSupported) "VideoStreaming: bulk IN=0, isochronous IN=$isochronous; native isochronous transport available, format/handshake not tested."
            else "VideoStreaming: bulk IN=0, isochronous IN=$isochronous; native isochronous transport unavailable on this build."
        video.isNotEmpty() -> "VideoStreaming: no bulk or isochronous IN endpoint exposed."
        interfaces.any { it.usbClass == 0x0e } -> "No standard VideoStreaming interface (class 0x0E, subclass 0x02) exposed."
        bulk > 0 -> "Non-UVC: bulk IN=$bulk; a matching vendor driver is required."
        isochronous > 0 -> "Non-UVC: isochronous IN=$isochronous; transfer support unverified."
        else -> "No bulk or isochronous IN stream candidate exposed."
    }
}

fun formatUsbDiagnostics(snapshot: UsbDiagnosticSnapshot): String = buildString {
    appendLine("USB Scope USB diagnostics")
    appendLine("App: ${oneLine(snapshot.appVersion)} (${snapshot.appVersionCode}), build ${oneLine(snapshot.appBuild)}")
    appendLine("Android: ${oneLine(snapshot.androidRelease)}, SDK ${snapshot.sdk}, OS build ${oneLine(snapshot.osBuild)}")
    appendLine("Device: ${oneLine(snapshot.manufacturer)} ${oneLine(snapshot.model)}")
    appendLine("USB host feature: ${snapshot.usbHostSupported}")
    if (snapshot.appPackage.isNotEmpty()) appendLine("App context: ${oneLine(snapshot.appPackage)}; UID=${snapshot.appUid}")
    appendLine("CAMERA permission granted: ${snapshot.cameraGranted}")
    appendLine("CAMERA app-op: ${oneLine(snapshot.cameraAppOp)}")
    appendLine("CAMERA app-op raw: ${oneLine(snapshot.cameraAppOpRaw)}")
    appendLine("App process importance: ${oneLine(snapshot.processImportance)}")
    appendLine("Camera privacy switch capabilities: ${oneLine(snapshot.cameraPrivacyCapability)}")
    appendLine("Camera privacy switch state: not readable through the public Android API")
    appendLine("Native isochronous transport available: ${snapshot.isochronousSupported}")
    if (snapshot.enumerationError != null) {
        appendLine("USB enumeration: ERROR (${oneLine(snapshot.enumerationError)})")
        appendLine("Attached USB devices: unknown")
        appendLine("Result: enumeration failed; this does not establish that no camera is attached.")
    } else {
        appendLine("USB enumeration: OK")
        appendLine("Attached USB devices: ${snapshot.devices.size}")
        if (snapshot.devices.isEmpty()) {
            appendLine("Result: Android exposes zero USB devices to this app. Driver selection has not started.")
        }
    }
    snapshot.devices.forEachIndexed { index, device ->
        appendLine()
        appendLine("USB device ${index + 1}: ${hex(device.vendorId, 4)}:${hex(device.productId, 4)}")
        device.attachmentLabel?.let { appendLine("  Attachment: ${oneLine(it)}") }
        appendLine("  Device class: ${usbClassSummary(device.usbClass)}, subclass=0x${hex(device.subclass, 2)}, protocol=0x${hex(device.protocol, 2)}")
        appendLine("  USB permission granted: ${device.usbPermissionGranted ?: "unknown"}")
        appendLine("  CAMERA permission required: ${device.cameraPermissionRequired ?: "unknown"}")
        appendLine("  DriverRegistry matches (alt 0): ${device.matchingDriverIds.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "none"}")
        val result = when {
            device.inspectionErrors.isNotEmpty() -> "Attached; read-only inspection incomplete."
            device.matchingDriverIds.isEmpty() -> "Attached, but unsupported by the current driver matching rules."
            device.cameraPermissionRequired == true && !snapshot.cameraGranted -> "Attached and matched; CAMERA permission is missing."
            device.usbPermissionGranted == false -> "Attached and matched; Android currently denies USB access. See the permission event history."
            device.usbPermissionGranted == null -> "Attached and matched; USB permission state is unknown."
            else -> "Attached and matched; permission gates open. Stream negotiation has not been tested."
        }
        appendLine("  Result: $result")
        appendLine("  ${usbVideoTransferSummary(device.interfaces, snapshot.isochronousSupported)}")
        appendLine("  Interface/alternate-setting entries: ${device.interfaces.size}")
        device.interfaces.sortedWith(compareBy<UsbDiagnosticInterface> { it.number }.thenBy { it.alternateSetting })
            .forEach { iface ->
                appendLine("  Interface ${iface.number}, alt ${iface.alternateSetting}: class=${usbClassSummary(iface.usbClass)}, " +
                    "subclass=0x${hex(iface.subclass, 2)}, protocol=0x${hex(iface.protocol, 2)}, endpoints=${iface.endpoints.size}")
                iface.endpoints.sortedBy { it.address }.forEach { appendLine("    ${usbEndpointSummary(it)}") }
            }
        device.inspectionErrors.forEach { appendLine("  Inspection error: ${oneLine(it)}") }
    }
    appendLine()
    append("Read-only report: no serial numbers, USB transfers, files/media, or network access used.")
}

private fun hex(value: Int, digits: Int): String = String.format(Locale.ROOT, "%0${digits}X", value)
private fun oneLine(value: String): String = value.replace(Regex("[\\r\\n\\t]+"), " ")
