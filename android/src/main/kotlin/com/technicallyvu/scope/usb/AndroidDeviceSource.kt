package com.technicallyvu.scope.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.technicallyvu.scope.core.usb.DeviceRef
import com.technicallyvu.scope.core.usb.DeviceSource
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbInterfaceInfo
import com.technicallyvu.scope.core.usb.UsbTransport
import java.util.concurrent.ConcurrentHashMap

class UsbPermissionException(val device: UsbDevice) : UsbException("USB permission not granted for ${device.deviceName}")

/** [DeviceSource] over [UsbManager]. `DeviceRef.address` carries Android's deviceId. */
class AndroidDeviceSource(
    private val usbManager: UsbManager,
    private val hasCameraPermission: () -> Boolean,
) : DeviceSource {
    private val openTransports = ConcurrentHashMap<String, AndroidUsbTransport>()

    /** Set when [open] found a device we lack permission for; the shell requests permission and clears it. */
    @Volatile var pendingPermission: UsbDevice? = null

    override fun list(): List<DeviceRef> {
        val attached = usbManager.deviceList.values
        // One authoritative polling observer; diagnostic snapshots do not mutate this history.
        UsbEventTrace.observeAttachments(attached.map {
            UsbObservedAttachment(identity(it), runCatching { usbManager.hasPermission(it) }.getOrNull())
        })
        pendingPermission?.let { pending ->
            if (attached.none { identity(it) == identity(pending) }) pendingPermission = null
        }
        return attached.map { d -> DeviceRef(info(d), bus = 0, address = d.deviceId) }
    }

    override fun open(ref: DeviceRef): UsbTransport {
        val device = usbManager.deviceList.values.firstOrNull { it.deviceId == ref.address && info(it) == ref.info }
            ?: throw UsbException("device ${ref.info.idString} is gone")
        val cameraGranted = hasCameraPermission()
        val usbGranted = usbManager.hasPermission(device)
        if ((requiresCameraPermission(device) && !cameraGranted) || !usbGranted) {
            UsbEventTrace.recordDeviceState("open blocked", identity(device), "CAMERA=$cameraGranted; USB=$usbGranted")
            pendingPermission = device
            throw UsbPermissionException(device)
        }
        pendingPermission = null
        UsbEventTrace.recordDevice("open begin", identity(device), "CAMERA=$cameraGranted; USB=$usbGranted")
        val connection = try {
            usbManager.openDevice(device)
        } catch (e: SecurityException) {
            UsbEventTrace.recordDevice("open security error", identity(device), e.javaClass.simpleName)
            // Permission can be revoked between hasPermission and openDevice. Return to the
            // permission UI instead of trapping the user in an automatic error/retry loop.
            pendingPermission = device
            throw UsbPermissionException(device)
        } ?: run {
            UsbEventTrace.recordDevice("open returned null", identity(device))
            throw UsbException("Could not open the USB camera. Reconnect it and try again.")
        }
        UsbEventTrace.recordDevice("open succeeded", identity(device))
        val real = RealUsbConnection(device, connection)
        val tracked = object : UsbConnection by real {
            override fun close() {
                try { real.close() }
                finally { UsbEventTrace.recordDevice("connection closed", identity(device)) }
            }
        }
        val transport = AndroidUsbTransport(tracked)
        openTransports[device.deviceName] = transport
        return transport
    }

    /** Called from the ACTION_USB_DEVICE_DETACHED receiver so an in-flight read fails fast. */
    fun onDetached(deviceName: String) {
        if (pendingPermission?.deviceName == deviceName) pendingPermission = null
        openTransports.remove(deviceName)?.detached = true
    }

    fun clearPermissionRequest(deviceName: String) {
        if (pendingPermission?.deviceName == deviceName) pendingPermission = null
    }

    companion object {
        fun identity(device: UsbDevice) = UsbPermissionDevice(
            device.deviceName, device.deviceId, device.vendorId, device.productId,
        )

        fun requiresCameraPermission(device: UsbDevice): Boolean = requiresCameraPermission(
            device.deviceClass,
            (0 until device.interfaceCount).map { device.getInterface(it).interfaceClass },
        )

        fun info(d: UsbDevice): UsbDeviceInfo {
            val interfaces = (0 until d.interfaceCount).map { d.getInterface(it) }
                .filter { it.alternateSetting == 0 }
                .map { UsbInterfaceInfo(it.id, it.interfaceClass, it.interfaceSubclass, it.interfaceProtocol) }
            return UsbDeviceInfo(d.vendorId, d.productId, d.deviceClass, interfaces)
        }
    }
}
