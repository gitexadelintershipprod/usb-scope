package com.technicallyvu.scope

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.technicallyvu.scope.settings.AppSettings
import com.technicallyvu.scope.ui.ScopeScreen
import com.technicallyvu.scope.ui.ScopeViewModel
import com.technicallyvu.scope.ui.theme.ScopeTheme
import com.technicallyvu.scope.usb.AndroidDeviceSource
import com.technicallyvu.scope.usb.UsbPermissionDevice
import com.technicallyvu.scope.usb.UsbPermissionRequest
import com.technicallyvu.scope.usb.UsbPermissionRequests
import com.technicallyvu.scope.usb.UsbPermissionStep
import java.util.UUID

class MainActivity : ComponentActivity() {
    // The one settings store: created here, owned by the view model (which outlives a rotation).
    private val vm: ScopeViewModel by viewModels {
        ScopeViewModel.Factory(application, AppSettings(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)))
    }
    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private var permissionRequests = UsbPermissionRequests()

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = permissionRequests.externalResult(UsbPermissionStep.Camera) ?: return@registerForActivityResult
        continueAfterCamera(request, granted && hasCameraPermission())
    }
    private val cameraSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val request = permissionRequests.externalResult(UsbPermissionStep.Settings) ?: return@registerForActivityResult
        continueAfterCamera(request, hasCameraPermission())
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            val request = permissionRequests.usbResult(
                intent.data?.lastPathSegment, device?.let { AndroidDeviceSource.identity(it) },
            ) ?: return
            val attached = connectedDevice(request.device)
            if (attached == null) {
                disconnected(request)
                return
            }
            // The result extra is not authority: permission may have been revoked or the camera
            // detached while the callback was queued. Always consult UsbManager before resuming.
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) &&
                canOpen(attached)
            finish(request, granted)
        }
    }
    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
            permissionRequests.detach(device.deviceName)?.let(::cancelUsbIntent)
            vm.onDeviceDetached(device.deviceName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restorePermissionRequest(savedInstanceState)
        enableEdgeToEdge()
        ContextCompat.registerReceiver(
            this, permissionReceiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // This receiver accepts only a protected system broadcast, never app-defined actions.
        registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
        setContent {
            ScopeTheme {
                val keepScreenOn by vm.keepScreenOn.collectAsState()
                LaunchedEffect(keepScreenOn) {
                    if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                ScopeScreen(vm, onRequestPermission = ::requestUsbPermission)
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun connectedDevice(identity: UsbPermissionDevice): UsbDevice? =
        usb.deviceList.values.firstOrNull { AndroidDeviceSource.identity(it) == identity }

    private fun canOpen(device: UsbDevice): Boolean =
        (!AndroidDeviceSource.requiresCameraPermission(device) || hasCameraPermission()) && usb.hasPermission(device)

    private fun requestUsbPermission(device: UsbDevice) {
        // Recover a stale request if the detach broadcast was missed while no activity existed.
        permissionRequests.pending?.let { pending ->
            if (connectedDevice(pending.device) == null) disconnected(pending)
        }
        val identity = AndroidDeviceSource.identity(device)
        val attached = connectedDevice(identity)
        if (attached == null) {
            permissionRequests.detach(device.deviceName)?.let(::cancelUsbIntent)
            vm.onDeviceDetached(device.deviceName)
            return
        }
        // A denial can be broadcast while this activity is being recreated. A fresh user tap
        // cancels only an outstanding USB request for this same attachment, allowing recovery.
        // Runtime camera/settings launchers remain guarded until their own callback arrives.
        permissionRequests.cancelUsbForRetry(identity)?.let(::cancelUsbIntent)
        if (canOpen(attached)) {
            vm.onPermissionResult(device.deviceName, true)
            return
        }
        val needsCamera = AndroidDeviceSource.requiresCameraPermission(attached) && !hasCameraPermission()
        val prefs = getPreferences(MODE_PRIVATE)
        val step = when {
            !needsCamera -> UsbPermissionStep.Usb
            prefs.getBoolean(CAMERA_ASKED, false) && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ->
                UsbPermissionStep.Settings
            else -> UsbPermissionStep.Camera
        }
        val request = UsbPermissionRequest(identity, UUID.randomUUID().toString(), step)
        // Polling and repeated taps must not stack system prompts or replace an in-flight device.
        if (!permissionRequests.begin(request)) return
        try {
            when (step) {
                UsbPermissionStep.Camera -> {
                    prefs.edit().putBoolean(CAMERA_ASKED, true).apply()
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }
                UsbPermissionStep.Settings -> cameraSettings.launch(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
                )
                UsbPermissionStep.Usb -> launchUsbRequest(request, attached)
            }
        } catch (e: RuntimeException) {
            if (step != UsbPermissionStep.Usb) permissionRequests.externalResult(step)
            finish(request, false, getString(R.string.status_usb_request_failed_format, e.message.orEmpty()))
        }
    }

    private fun continueAfterCamera(request: UsbPermissionRequest, granted: Boolean) {
        val device = connectedDevice(request.device)
        when {
            device == null -> disconnected(request)
            !granted -> finish(request, false, getString(R.string.status_camera_permission_denied))
            canOpen(device) -> finish(request, true)
            else -> permissionRequests.startUsb(request)?.let { usbRequest ->
                try {
                    launchUsbRequest(usbRequest, device)
                } catch (e: RuntimeException) {
                    finish(usbRequest, false, getString(R.string.status_usb_request_failed_format, e.message.orEmpty()))
                }
            }
        }
    }

    private fun usbIntent(request: UsbPermissionRequest): Intent =
        Intent(ACTION_USB_PERMISSION).setPackage(packageName)
            .setData(Uri.parse("scope-usb-permission://$packageName/${request.token}"))

    private fun launchUsbRequest(request: UsbPermissionRequest, device: UsbDevice) {
        // A package-scoped mutable, one-shot intent lets Android supply the result extras while
        // limiting the capability to this request. Its unique URI rejects stale/replayed results.
        val pi = PendingIntent.getBroadcast(
            this, request.device.id, usbIntent(request),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_CANCEL_CURRENT,
        )
        usb.requestPermission(device, pi)
    }

    private fun cancelUsbIntent(request: UsbPermissionRequest) {
        if (request.step != UsbPermissionStep.Usb) return
        PendingIntent.getBroadcast(
            this, request.device.id, usbIntent(request),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_NO_CREATE,
        )?.cancel()
    }

    private fun disconnected(request: UsbPermissionRequest) {
        if (!permissionRequests.finish(request)) return
        cancelUsbIntent(request)
        vm.onDeviceDetached(request.device.name)
    }

    private fun finish(
        request: UsbPermissionRequest,
        granted: Boolean,
        deniedMessage: String? = null,
    ) {
        if (!permissionRequests.finish(request)) return
        cancelUsbIntent(request)
        vm.onPermissionResult(request.device.name, granted, deniedMessage)
    }

    override fun onResume() {
        super.onResume()
        // A USB reply may have arrived during activity recreation, before receiver registration.
        // Resume only when the same attachment still exists and both permission gates are open.
        permissionRequests.pending?.takeIf { it.step == UsbPermissionStep.Usb }?.let { request ->
            val device = connectedDevice(request.device)
            if (device == null) disconnected(request)
            else if (canOpen(device)) finish(request, true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_EXTERNAL_TOKEN, permissionRequests.externalActivityToken)
        permissionRequests.pending?.let { request ->
            outState.putString(STATE_DEVICE_NAME, request.device.name)
            outState.putInt(STATE_DEVICE_ID, request.device.id)
            outState.putInt(STATE_VENDOR_ID, request.device.vendorId)
            outState.putInt(STATE_PRODUCT_ID, request.device.productId)
            outState.putString(STATE_TOKEN, request.token)
            outState.putString(STATE_STEP, request.step.name)
        }
    }

    private fun restorePermissionRequest(saved: Bundle?) {
        if (saved == null) return
        val request = saved.getString(STATE_DEVICE_NAME)?.let { name ->
            val token = saved.getString(STATE_TOKEN) ?: return@let null
            val step = saved.getString(STATE_STEP)?.let { value -> UsbPermissionStep.entries.find { it.name == value } }
                ?: return@let null
            UsbPermissionRequest(
                UsbPermissionDevice(name, saved.getInt(STATE_DEVICE_ID), saved.getInt(STATE_VENDOR_ID), saved.getInt(STATE_PRODUCT_ID)),
                token, step,
            )
        }
        permissionRequests = UsbPermissionRequests(request, saved.getString(STATE_EXTERNAL_TOKEN))
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        unregisterReceiver(detachReceiver)
        super.onDestroy()
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.technicallyvu.scope.USB_PERMISSION"
        const val PREFS_NAME = "scope_settings"
        private const val CAMERA_ASKED = "camera_permission_asked"
        private const val STATE_EXTERNAL_TOKEN = "usb_external_token"
        private const val STATE_DEVICE_NAME = "usb_device_name"
        private const val STATE_DEVICE_ID = "usb_device_id"
        private const val STATE_VENDOR_ID = "usb_vendor_id"
        private const val STATE_PRODUCT_ID = "usb_product_id"
        private const val STATE_TOKEN = "usb_request_token"
        private const val STATE_STEP = "usb_request_step"
    }
}
