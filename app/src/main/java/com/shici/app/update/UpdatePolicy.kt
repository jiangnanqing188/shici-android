package com.shici.app.update

/**
 * Fixed distribution channel of this build. The value comes from the build's own non-localized
 * string resource ([com.shici.app.R.string.update_channel]), never from locale or device shape, so
 * the tablet build can only ever see tablet releases and the phone build only phone releases.
 */
enum class UpdateChannel(val id: String) {
    TABLET("tablet"),
    PHONE("phone");

    companion object {
        /** Null for an absent/unknown value: update checks are then disabled, never guessed. */
        fun fromId(raw: String?): UpdateChannel? {
            val value = raw?.trim() ?: return null
            return entries.firstOrNull { it.id == value }
        }
    }
}

/** A published release that passed every structural and metadata check for this channel. */
data class UpdateRelease(
    val tag: String,
    val versionName: String,
    val versionCode: Int,
    val assetName: String,
    val sizeBytes: Long,
    val sha256: String,
    /** Exact, pre-validated HTTPS asset URL; the only URL the downloader will ever open. */
    val downloadUrl: String
)

/** Outcome of matching the release list against this build. */
sealed class UpdateDecision {
    data class Available(val release: UpdateRelease) : UpdateDecision()

    /** No release with a strictly greater version code is published for this channel. */
    data object UpToDate : UpdateDecision()

    /** The repository holds no published release tagged for this channel at all. */
    data object NoRelease : UpdateDecision()

    /** Two published tags share the highest version code; picking either would be arbitrary. */
    data class Ambiguous(val tags: List<String>, val versionCode: Int) : UpdateDecision()

    /** The highest published candidate is unusable (bad asset/digest/size/URL/state). */
    data class InvalidMetadata(val tag: String?, val detail: String) : UpdateDecision()
}

/**
 * Pure, host-testable release selection.
 *
 * A published release is a candidate only when: its tag is
 * `v<major.minor.patch>-<channel>-test`, it is not a draft (a prerelease is expected — that is what
 * a test build is), and it carries exactly one asset named
 * `shici-<channel>-<versionName>-<versionCode>.apk`. The candidate set is the numerically highest
 * `versionCode`; it must be the only tag claiming that code, must be strictly greater than the
 * installed version code, and its asset must be `state=uploaded` with a `sha256:` digest, a size
 * inside [MAX_APK_BYTES] and the exact GitHub asset URL of this repository.
 *
 * Anything wrong with the highest candidate is reported instead of quietly falling back to an
 * older, valid package: this build never guesses which release the publisher meant.
 */
object UpdatePolicy {
    const val REPOSITORY = "jiangnanqing188/shici-android"
    const val API_URL = "https://api.github.com/repos/$REPOSITORY/releases?per_page=100"
    const val RELEASE_DOWNLOAD_PREFIX = "https://github.com/$REPOSITORY/releases/download/"

    /** Upper bound for a downloaded APK; the current builds are about 158 MB. */
    const val MAX_APK_BYTES: Long = 300L * 1024 * 1024
    private const val MAX_VERSION_CODE = 2_100_000_000

    fun tagPattern(channel: UpdateChannel): Regex =
        Regex("^v([0-9]{1,4})\\.([0-9]{1,4})\\.([0-9]{1,4})-${Regex.escape(channel.id)}-test$")

    fun assetName(versionName: String, versionCode: Int, channel: UpdateChannel): String =
        "shici-${channel.id}-$versionName-$versionCode.apk"

    private fun assetPattern(channel: UpdateChannel, versionName: String): Regex =
        Regex("^shici-${Regex.escape(channel.id)}-${Regex.escape(versionName)}-([0-9]{1,10})\\.apk$")

    fun downloadUrl(tag: String, assetName: String): String =
        "$RELEASE_DOWNLOAD_PREFIX$tag/$assetName"

    private data class Candidate(val tag: String, val versionName: String, val asset: ReleaseAsset, val code: Int)

    fun decide(
        releases: List<ReleaseEntry>,
        channel: UpdateChannel,
        installedVersionCode: Int
    ): UpdateDecision {
        val pattern = tagPattern(channel)
        val candidates = ArrayList<Candidate>()
        for (entry in releases) {
            if (entry.draft) continue
            val match = pattern.matchEntire(entry.tag) ?: continue
            val versionName = match.groupValues.drop(1).joinToString(".") { it.toInt().toString() }
            val assetPattern = assetPattern(channel, versionName)
            val matching = entry.assets.filter { assetPattern.matches(it.name) }
            if (entry.tag != "v$versionName-${channel.id}-test" || matching.size != 1) {
                // No reliable versionCode for this published tag. Don't guess whether it is older.
                return UpdateDecision.InvalidMetadata(entry.tag, "发布必须包含一个名称规范的本渠道 APK")
            }
            val asset = matching.single()
            val code = assetPattern.matchEntire(asset.name)!!.groupValues[1].toIntOrNull()
            if (code == null || code !in 1..MAX_VERSION_CODE || asset.name != assetName(versionName, code, channel)) {
                return UpdateDecision.InvalidMetadata(entry.tag, "安装包名称中的版本号无效")
            }
            candidates.add(Candidate(entry.tag, versionName, asset, code))
        }
        val highest = candidates.maxOfOrNull { it.code } ?: return UpdateDecision.NoRelease
        if (highest <= installedVersionCode) return UpdateDecision.UpToDate
        val top = candidates.filter { it.code == highest }
        if (top.size > 1) return UpdateDecision.Ambiguous(top.map { it.tag }.sorted(), highest)
        val chosen = top.single()
        val asset = chosen.asset
        if (asset.state != "uploaded") return UpdateDecision.InvalidMetadata(chosen.tag, "安装包未上传完成")
        if (asset.size !in 1..MAX_APK_BYTES) return UpdateDecision.InvalidMetadata(chosen.tag, "安装包大小缺失或超出上限")
        val sha256 = sha256Of(asset.digest)
            ?: return UpdateDecision.InvalidMetadata(chosen.tag, "缺少有效的 SHA-256 校验值")
        val url = downloadUrl(chosen.tag, asset.name)
        if (asset.downloadUrl != url) return UpdateDecision.InvalidMetadata(chosen.tag, "下载地址不是本仓库的固定资产地址")
        return UpdateDecision.Available(UpdateRelease(
            chosen.tag, chosen.versionName, highest, asset.name, asset.size, sha256, url
        ))
    }

    private fun sha256Of(digest: String?): String? {
        val value = digest ?: return null
        if (!value.startsWith("sha256:")) return null
        val hex = value.removePrefix("sha256:")
        if (hex.length != 64) return null
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return hex
    }
}
