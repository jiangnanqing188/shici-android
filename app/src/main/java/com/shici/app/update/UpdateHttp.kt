package com.shici.app.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** A network/metadata failure with a kind the UI can turn into an honest, distinct message. */
internal class UpdateHttpException(val kind: Kind, message: String) : IOException(message) {
    enum class Kind {
        /** Offline, DNS, TLS, connection reset, timeout. */
        NETWORK,
        /** Non-200 response that is not rate limiting. */
        HTTP,
        /** GitHub refused the request (HTTP 403/429). Never reported as "already up to date". */
        RATE_LIMITED,
        /** Redirect outside the fixed allow-list, or too many hops. */
        REDIRECT,
        /** Declared/actual size cap or declared size mismatch. */
        LIMIT,
        /** Downloaded bytes do not match the published SHA-256 / size. */
        VERIFY,
        /** The user or the Activity lifecycle cancelled the transfer. */
        CANCELLED
    }
}

/**
 * The only network code in the update module.
 *
 * - The API request goes to exactly [UpdatePolicy.API_URL] and a redirect there is an error.
 * - A download starts at the one exact asset URL chosen by [UpdatePolicy] and may only follow
 *   redirects that stay on HTTPS/standard port, carry no userinfo and land on GitHub's own
 *   release-asset hosts. Any other host aborts before a byte is read.
 * - No credentials, cookies or device/user identifiers are ever sent; the only headers are a
 *   product User-Agent, the media type and the API version.
 * - Every read is bounded by the declared asset size, and the body is capped independently.
 */
internal object UpdateHttp {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val CHECK_MS = 45_000L
    private const val DOWNLOAD_MS = 15L * 60 * 1000
    private const val MAX_REDIRECTS = 5
    private const val BUFFER = 64 * 1024
    private const val USER_AGENT = "shici-android-update-check"
    private const val HEX = "0123456789abcdef"

    private val API_HOSTS = setOf("api.github.com")
    private val DOWNLOAD_HOSTS = setOf(
        "release-assets.githubusercontent.com",
        "objects.githubusercontent.com"
    )

    /** Reads the release list into memory under [maxBytes]; never larger than that. */
    fun getApi(maxBytes: Int, isCancelled: () -> Boolean = { false }): String {
        val guard = TransferGuard(CHECK_MS, isCancelled)
        guard.check()
        val connection = open(UpdatePolicy.API_URL, API_HOSTS, api = true)
        try {
            val code = fromNetwork { connection.responseCode }
            guard.check()
            if (code in 300..399) throw UpdateHttpException(
                UpdateHttpException.Kind.REDIRECT, "发布列表接口返回了重定向"
            )
            if (code == 403 || code == 429) throw UpdateHttpException(
                UpdateHttpException.Kind.RATE_LIMITED, "GitHub 暂时拒绝请求（HTTP $code），并非已是最新"
            )
            if (code != 200) throw UpdateHttpException(
                UpdateHttpException.Kind.HTTP, "发布列表接口返回 HTTP $code"
            )
            if (connection.contentLengthLong > maxBytes) throw UpdateHttpException(
                UpdateHttpException.Kind.LIMIT, "发布列表响应超过大小上限"
            )
            val out = ByteArrayOutputStream(64 * 1024)
            val buffer = ByteArray(BUFFER)
            fromNetwork { connection.inputStream }.use { input ->
                while (true) {
                    guard.check()
                    val read = fromNetwork { input.read(buffer) }
                    guard.check()
                    if (read < 0) break
                    if (out.size() + read > maxBytes) throw UpdateHttpException(
                        UpdateHttpException.Kind.LIMIT, "发布列表响应超过大小上限"
                    )
                    out.write(buffer, 0, read)
                }
            }
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(out.toByteArray())).toString()
            } catch (_: CharacterCodingException) {
                throw UpdateHttpException(UpdateHttpException.Kind.VERIFY, "发布列表不是有效 UTF-8")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Streams the verified asset URL into [destination] and returns the actual SHA-256 hex.
     *
     * The transfer never writes more than [UpdateRelease.sizeBytes] and fails if the server
     * declares a different size, if the body is shorter/longer, or if the digest differs. The
     * caller owns and must discard [destination] on any exception.
     */
    fun download(
        release: UpdateRelease,
        destination: File,
        isCancelled: () -> Boolean,
        onProgress: (Long) -> Unit
    ): String {
        val guard = TransferGuard(DOWNLOAD_MS, isCancelled)
        guard.check()
        if (release.downloadUrl != UpdatePolicy.downloadUrl(release.tag, release.assetName) ||
            release.sizeBytes !in 1..UpdatePolicy.MAX_APK_BYTES ||
            !SHA256.matches(release.sha256) || release.tag.any { it !in TAG_CHARS } ||
            release.assetName.any { it !in TAG_CHARS }) {
            throw UpdateHttpException(UpdateHttpException.Kind.REDIRECT, "下载地址或发布信息不合规")
        }
        var hops = 0
        var connection = open(release.downloadUrl, setOf("github.com"), api = false)
        try {
            while (true) {
                guard.check()
                val code = fromNetwork { connection.responseCode }
                guard.check()
                if (code in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location")
                        ?: throw UpdateHttpException(
                            UpdateHttpException.Kind.REDIRECT, "下载重定向缺少目标地址"
                        )
                    val next = try {
                        URL(connection.url, location).toString()
                    } catch (invalid: Exception) {
                        throw UpdateHttpException(
                            UpdateHttpException.Kind.REDIRECT, "下载重定向地址无效"
                        )
                    }
                    connection.disconnect()
                    if (++hops > MAX_REDIRECTS) throw UpdateHttpException(
                        UpdateHttpException.Kind.REDIRECT, "下载重定向次数过多"
                    )
                    connection = open(next, DOWNLOAD_HOSTS, api = false)
                    continue
                }
                val declared = connection.contentLengthLong
                if (code == 403 || code == 429) throw UpdateHttpException(
                    UpdateHttpException.Kind.RATE_LIMITED, "GitHub 暂时拒绝下载（HTTP $code）"
                )
                if (code != 200) throw UpdateHttpException(
                    UpdateHttpException.Kind.HTTP, "下载安装包失败（HTTP $code）"
                )
                if (declared >= 0 && declared != release.sizeBytes) throw UpdateHttpException(
                    UpdateHttpException.Kind.LIMIT, "服务器声明的大小与发布信息不一致"
                )
                return readBody(release, connection, destination, guard, onProgress)
            }
        } catch (failure: IOException) {
            guard.check()
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    private fun readBody(
        release: UpdateRelease,
        connection: HttpURLConnection,
        destination: File,
        guard: TransferGuard,
        onProgress: (Long) -> Unit
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        var total = 0L
        FileOutputStream(destination).use { file ->
            fromNetwork { connection.inputStream }.use { input ->
                while (true) {
                    guard.check()
                    val read = fromNetwork { input.read(buffer) }
                    guard.check()
                    if (read < 0) break
                    total += read
                    if (total > release.sizeBytes) throw UpdateHttpException(
                        UpdateHttpException.Kind.LIMIT, "下载内容超过发布声明的大小"
                    )
                    file.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    onProgress(total)
                }
            }
        }
        if (total != release.sizeBytes) throw UpdateHttpException(
            UpdateHttpException.Kind.LIMIT, "下载内容长度与发布声明不一致"
        )
        val actual = hex(digest.digest())
        if (actual != release.sha256) throw UpdateHttpException(
            UpdateHttpException.Kind.VERIFY, "下载内容的 SHA-256 与发布声明不一致"
        )
        return actual
    }

    private fun open(
        url: String,
        allowedHosts: Set<String>,
        api: Boolean
    ): HttpURLConnection {
        validate(url, allowedHosts)
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (invalid: Exception) {
            throw UpdateHttpException(UpdateHttpException.Kind.REDIRECT, "下载地址无效")
        }
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        // No Authorization, no Cookie: the request carries no credentials at all.
        connection.setRequestProperty("User-Agent", USER_AGENT)
        if (api) {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        } else {
            connection.setRequestProperty("Accept-Encoding", "identity")
        }
        return connection
    }

    /** HTTPS only, standard port only, no userinfo, exact host allow-list. */
    private fun validate(url: String, allowedHosts: Set<String>) {
        val parsed = try {
            URL(url)
        } catch (invalid: Exception) {
            throw UpdateHttpException(UpdateHttpException.Kind.REDIRECT, "下载地址无效")
        }
        val host = parsed.host.lowercase()
        val ok = parsed.protocol == "https" &&
            (parsed.port == -1 || parsed.port == 443) &&
            parsed.userInfo == null && parsed.ref == null &&
            host in allowedHosts
        if (!ok) throw UpdateHttpException(
            UpdateHttpException.Kind.REDIRECT, "拒绝非本仓库固定 HTTPS 地址的请求"
        )
    }

    private inline fun <T> fromNetwork(action: () -> T): T = try {
        action()
    } catch (_: IOException) {
        throw UpdateHttpException(UpdateHttpException.Kind.NETWORK, "无法连接 GitHub 或网络已中断")
    }

    private val SHA256 = Regex("[a-f0-9]{64}")
    private val TAG_CHARS = ('a'..'z').toSet() + ('0'..'9').toSet() + setOf('.', '-')

    private class TransferGuard(timeMs: Long, private val isCancelled: () -> Boolean) {
        private val deadline = System.nanoTime() + timeMs * 1_000_000

        fun check() {
            if (isCancelled()) throw UpdateHttpException(UpdateHttpException.Kind.CANCELLED, "操作已取消")
            if (System.nanoTime() - deadline >= 0) throw UpdateHttpException(
                UpdateHttpException.Kind.NETWORK, "更新请求超时"
            )
        }
    }

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return out.toString()
    }
}
