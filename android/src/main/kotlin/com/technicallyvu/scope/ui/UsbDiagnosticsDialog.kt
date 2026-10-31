package com.technicallyvu.scope.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.hardware.usb.UsbDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.technicallyvu.scope.R
import com.technicallyvu.scope.usb.UsbDiagnostics
import com.technicallyvu.scope.usb.UsbDiagnosticsResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/** User-opened, local-only USB inventory. It never opens or probes a device. */
@Composable
fun UsbDiagnosticsDialog(
    status: String,
    onDismiss: () -> Unit,
    onRequestPermission: (UsbDevice) -> Unit,
) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<UsbDiagnosticsResult?>(null) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            result = withContext(Dispatchers.IO) { UsbDiagnostics.collect(context.applicationContext) }
            delay(1_000)
        }
    }
    val report = result?.let { "${it.report}\nViewer status: $status\n" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.usb_diagnostics_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.usb_diagnostics_help))
                result?.devices?.forEach { device ->
                    val id = String.format(Locale.ROOT, "%04X:%04X", device.vendorId, device.productId)
                    OutlinedButton(
                        onClick = { onRequestPermission(device) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.usb_diagnostics_allow_format, id)) }
                }
                SelectionContainer {
                    Text(
                        report ?: stringResource(R.string.usb_diagnostics_loading),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = report != null,
                onClick = {
                    report?.let {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("USB Scope diagnostics", it))
                        copied = true
                    }
                },
            ) {
                Text(stringResource(if (copied) R.string.usb_diagnostics_copied else R.string.usb_diagnostics_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}
