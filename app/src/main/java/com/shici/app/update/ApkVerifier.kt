package com.shici.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Checks a downloaded file is really *this* app, at exactly the published version, signed by the
 * same set of certificates as the installed build. Nothing here trusts the download source: an APK
 * that fails any check is discarded and never handed to the installer.
 *
 * API 26/27 read legacy `signatures`; API 28+ read `SigningInfo.apkContentsSigners`, which is the
 * certificate set actually used to sign the APK. A rotated/other-key re-sign (or a debug build)
 * therefore never matches the installed app and is refused instead of being "upgraded".
 */
internal object ApkVerifier {
    private const val CHANNEL_KEY = "com.shici.app.UPDATE_CHANNEL"

    /** Null when acceptable; otherwise a short, user-showable reason. */
    fun verify(
        context: Context,
        file: File,
        expectedVersionCode: Int,
        expectedVersionName: String,
        channel: UpdateChannel
    ): String? {
        if (!file.isFile || file.length() <= 0L) return "安装包文件缺失"
        val archive = archiveInfo(context, file) ?: return "无法读取安装包信息"
        if (archive.packageName != context.packageName) return "安装包不是本应用"
        if (archive.applicationInfo?.metaData?.getString(CHANNEL_KEY) != channel.id) {
            return "安装包的手机/平板渠道不匹配（或未标注渠道）"
        }
        val versionCode = if (Build.VERSION.SDK_INT >= 28) {
            archive.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archive.versionCode.toLong()
        }
        if (versionCode != expectedVersionCode.toLong()) return "安装包版本号与发布信息不一致"
        if (archive.versionName != expectedVersionName) return "安装包版本名与发布信息不一致"

        val archiveSigners = signerDigests(archive)
        if (archiveSigners.isEmpty()) return "安装包缺少签名"
        val installed = installedInfo(context) ?: return "无法读取已安装应用的签名"
        val installedCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode
            else @Suppress("DEPRECATION") installed.versionCode.toLong()
        if (versionCode <= installedCode) return "安装包版本不比已安装版本新"
        val installedSigners = signerDigests(installed)
        if (installedSigners.isEmpty()) return "已安装应用缺少签名"
        if (archiveSigners != installedSigners) return "安装包签名与已安装应用不一致"
        return null
    }

    private fun archiveInfo(context: Context, file: File): PackageInfo? {
        val flags = signatureFlags() or PackageManager.GET_META_DATA
        val manager = context.packageManager
        return if (Build.VERSION.SDK_INT >= 33) {
            manager.getPackageArchiveInfo(
                file.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageArchiveInfo(file.absolutePath, flags)
        }
    }

    private fun installedInfo(context: Context): PackageInfo? {
        val flags = signatureFlags()
        val manager = context.packageManager
        return if (Build.VERSION.SDK_INT >= 33) {
            manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(context.packageName, flags)
        }
    }

    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    /** SHA-256 of each signing certificate, as a comparable set. */
    private fun signerDigests(info: PackageInfo): Set<String> {
        val certificates = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty()
        }
        return certificates.mapTo(LinkedHashSet()) { signature ->
            val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        }
    }
}
