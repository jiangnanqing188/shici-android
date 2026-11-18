package com.shici.app.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands one already-verified APK to Android's own package installer.
 *
 * The file is shared read-only through a scoped [FileProvider] content URI and a one-shot read
 * grant; no `file://` URI, no world-readable directory and no storage permission are involved. This
 * object never installs anything itself: it only starts the system installer, whose own confirmation
 * screen decides.
 */
internal object UpdateInstaller {
    private const val AUTHORITY_SUFFIX = ".updates"
    private const val APK_MIME = "application/vnd.android.package-archive"

    fun contentUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)

    /** Whether the user currently allows this app to install packages at all. */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    @Suppress("DEPRECATION") // Resolves only to a system installer for this content URI.
    fun installerIntent(context: Context, uri: Uri): Intent {
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val installer = context.packageManager.resolveActivity(
            intent, PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo ?: throw android.content.ActivityNotFoundException("系统安装器不可用")
        intent.setClassName(installer.packageName, installer.name)
        // Clip data makes the temporary read grant travel with the intent on every API level.
        intent.clipData = ClipData.newRawUri("verified update", uri)
        return intent
    }

    /** This app's own "install unknown apps" page; the user must enable it and come back. */
    fun unknownSourcesIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
}
