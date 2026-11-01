package com.technicallyvu.scope.usb

import android.content.Intent
import android.content.IntentFilter
import android.net.Uri

/** Keeps the private result receiver's data filter aligned with its token-bearing PendingIntent. */
object UsbPermissionIntents {
    const val ACTION = "com.technicallyvu.scope.USB_PERMISSION"
    private const val SCHEME = "scope-usb-permission"

    @JvmStatic
    fun createFilter(packageName: String): IntentFilter = IntentFilter(ACTION).apply {
        // An action-only filter rejects intents that carry any URI. Match this app's private
        // scheme/authority; the receiver still checks the per-request token and USB identity.
        addDataScheme(SCHEME)
        addDataAuthority(packageName, null)
    }

    @JvmStatic
    fun createIntent(packageName: String, token: String): Intent = Intent(ACTION)
        .setPackage(packageName)
        .setData(Uri.Builder().scheme(SCHEME).authority(packageName).appendPath(token).build())
}
