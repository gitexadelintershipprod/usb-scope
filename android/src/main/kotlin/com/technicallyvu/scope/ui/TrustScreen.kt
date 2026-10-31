package com.technicallyvu.scope.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.technicallyvu.scope.BuildConfig
import com.technicallyvu.scope.R
import com.technicallyvu.scope.core.TrustInfo

/**
 * "About & privacy": what the app requests from the OS, what it does with the network (nothing),
 * and who the reverse-engineered protocol work is credited to. Read-only; a [LazyColumn] since the
 * license text alone is long enough to need scrolling on a phone.
 *
 * Structured like [SettingsScreen] — [Scaffold] + [TopAppBar], `safeDrawing` insets — and for one
 * load-bearing reason beyond consistency: a bare `Column` paints no background and sets no content
 * colour, so in dark mode this screen drew near-black default text onto the black window and was
 * simply invisible. The Scaffold supplies `colorScheme.background` and the matching `onBackground`,
 * so every `Text` below inherits a colour that contrasts in both themes.
 *
 * @param devControls debug builds only (the caller passes null otherwise): long-pressing the
 * version line then opens the developer sheet. In a release build the long-press does not exist —
 * the modifier is not applied and [DevSheet] is never called.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TrustScreen(
    onBack: () -> Unit,
    devControls: DevControls? = null,
    onUsbDiagnostics: () -> Unit = {},
) {
    BackHandler(enabled = true, onBack = onBack)
    val context = LocalContext.current
    // The merged manifest asks for more than `src/main` declares: AndroidX injects
    // "<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" so ContextCompat can keep an
    // internal broadcast inside this process. Counting it made this screen say "Permissions
    // requested: 1" for an app that asks the system for nothing at all. See countSystemPermissions.
    val permissions = remember {
        runCatching {
            val requested = context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
            val counted = countSystemPermissions(context.packageName, requested)
            counted to ((requested?.size ?: 0) - counted)
        }.getOrDefault(0 to 0)
    }
    val (requestedPermissions, appPrivatePermissions) = permissions
    // Reflowed once: `TrustInfo` keeps the licence hard-wrapped at ~80 columns (that is how MIT is
    // distributed), which on a phone wraps a second time and looks broken. See reflowParagraphs.
    val noNetworkStatement = remember { reflowParagraphs(TrustInfo.noNetworkStatement) }
    val protocolNote = remember { reflowParagraphs(TrustInfo.protocolNote) }
    val licenseText = remember { reflowParagraphs(TrustInfo.licenseText) }
    var showDevSheet by remember { mutableStateOf(false) }

    Scaffold(
        // enableEdgeToEdge() (and targetSdk 36, which enforces it regardless) draws this screen
        // behind the status and gesture bars, and the theme opts into the display cutout, so
        // safeDrawing rather than the Scaffold's systemBars default: the bar keeps clear of the
        // clock and a notch, and the insets below keep the last line of the licence clear of the
        // gesture bar.
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                // The developer sheet's only door, and only in a debug build: a long-press here.
                // Nothing advertises it, and a release build attaches no gesture at all.
                val versionModifier =
                    if (devControls != null && BuildConfig.DEBUG) {
                        Modifier
                            // A bodyMedium line box is ~20 dp; the global 48 dp minimum applies to
                            // a hidden gesture too, and a small target makes the emulator's
                            // `input swipe` verification flaky.
                            .heightIn(min = 48.dp)
                            .combinedClickable(
                                // A plain tap must keep doing nothing; only the long-press opens the sheet.
                                onClick = {},
                                onLongClick = { showDevSheet = true },
                                onLongClickLabel = stringResource(R.string.cd_open_developer_options),
                            )
                            .wrapContentHeight(Alignment.CenterVertically)
                    } else {
                        Modifier
                    }
                Text(
                    stringResource(
                        R.string.about_version_format,
                        BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.GIT_SHA,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = versionModifier,
                )
                Text(
                    stringResource(R.string.about_permissions_requested_format, requestedPermissions),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Only when something really was excluded, so the footnote is never an unexplained
                // disclaimer about a thing that did not happen.
                if (appPrivatePermissions > 0) {
                    Text(
                        stringResource(R.string.about_permissions_internal_footnote),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Button(onClick = onUsbDiagnostics) { Text(stringResource(R.string.usb_diagnostics_title)) }
            }
            item { HorizontalDivider() }
            item { Text(noNetworkStatement, style = MaterialTheme.typography.bodyMedium) }
            item {
                Column {
                    Text(
                        stringResource(R.string.about_permissions_not_requested_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    TrustInfo.permissionsNotRequested.forEach { perm ->
                        Text(
                            stringResource(R.string.about_bullet_format, perm.removePrefix("android.permission.")),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item {
                // No donation link in the Android app: Google Play's Payments policy forbids in-app
                // links to external payment methods (see docs/play-policy-audit.md). Sponsorship
                // lives on the GitHub repository.
                val sourceUrl = stringResource(R.string.source_url)
                Text(
                    sourceUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    // Every tappable line on this screen carries the theme's primary colour, so
                    // "this opens a browser" is visible without relying on the tap to discover it.
                    color = MaterialTheme.colorScheme.primary,
                    // Same 48 dp minimum as the version line above: a bodyMedium line box is ~20 dp,
                    // and the plan's global constraint applies to a link as much as to a button.
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { openLink(context, sourceUrl) }
                        .wrapContentHeight(Alignment.CenterVertically),
                )
            }
            item { HorizontalDivider() }
            item { Text(protocolNote, style = MaterialTheme.typography.bodyMedium) }
            item { Text(stringResource(R.string.about_attributions_title), style = MaterialTheme.typography.titleSmall) }
            items(TrustInfo.attributions) { a ->
                Column {
                    Text(
                        a.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button) { openLink(context, a.url) }
                            .wrapContentHeight(Alignment.CenterVertically),
                    )
                    Text(
                        stringResource(R.string.about_attribution_detail_format, a.license, a.note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { HorizontalDivider() }
            item { Text(stringResource(R.string.about_license_title), style = MaterialTheme.typography.titleSmall) }
            // Body font, not monospace: this is prose, and a fixed-pitch column at ~80 characters
            // is unreadable on a phone. Reflowed above so it wraps to the screen instead of to the
            // source file's line endings.
            item { Text(licenseText, style = MaterialTheme.typography.bodySmall) }
        }
    }

    if (BuildConfig.DEBUG && showDevSheet && devControls != null) {
        DevSheet(devControls, onDismiss = { showDevSheet = false })
    }
}

/**
 * Hands [url] to whatever can view it. A device with no browser (or with links disabled by policy)
 * throws ActivityNotFoundException from startActivity; the tap then does nothing and the URL stays
 * on screen as plain text to copy, which must never be worth crashing the app over.
 */
private fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { /* no browser; show the URL text as-is */ }
}
