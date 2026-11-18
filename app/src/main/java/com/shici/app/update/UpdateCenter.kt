package com.shici.app.update

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Why an update interaction failed, so the UI can be specific instead of saying "出错". */
enum class UpdateFailureKind { NETWORK, RATE_LIMITED, METADATA, STORAGE, CANCELLED, UNSUPPORTED }

/** Everything the update surface can show. Published to the Activity only while it is started. */
sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    /** A channel release exists but nothing is strictly newer than the installed build. */
    data object UpToDate : UpdateState()
    /** The repository holds no published release for this build's channel yet. */
    data object NoRelease : UpdateState()
    data class Available(val release: UpdateRelease) : UpdateState()
    data class Failure(val kind: UpdateFailureKind, val message: String) : UpdateState()
    data class Downloading(
        val release: UpdateRelease,
        val bytes: Long,
        val total: Long
    ) : UpdateState()
    /** Downloaded, size/SHA-256 and package/signature verified; not yet handed to the installer. */
    data class Ready(val release: UpdateRelease, val file: File) : UpdateState()
    /** Verified, but Android does not currently allow this app to install packages. */
    data class InstallBlocked(
        val release: UpdateRelease,
        val file: File,
        val notice: String? = null
    ) : UpdateState()
    /** The system installer was opened. This is not "installed"; the user must confirm there. */
    data class InstallerOpened(val release: UpdateRelease, val file: File) : UpdateState()
    data class InstallFailed(
        val release: UpdateRelease,
        val file: File,
        val message: String
    ) : UpdateState()
    /** The build has no usable channel; update checking stays off instead of guessing. */
    data object Disabled : UpdateState()
}

/**
 * Foreground-only update coordinator.
 *
 * Responsibilities kept deliberately narrow: a frequency-limited GitHub release check, one
 * user-initiated verified download into the app cache, and handing that file to the system
 * installer. It starts no service, shows no dialog by itself, requests no notification permission
 * and never installs silently.
 *
 * Threading and lifecycle, matching the rest of the app:
 * - all [state] writes and listener calls happen on the main thread;
 * - network and file work run on a private single thread, not on the app's existing worker;
 * - [onBackground] invalidates the generation; network cancellation is observed on the worker,
 *   never by disconnecting on the UI thread. A late result cannot open UI or an installer.
 * - The process-wide worker serializes transfers even across Activity recreation.
 */
class UpdateCenter(
    private val activity: Activity,
    private val channel: UpdateChannel?,
    private val installedVersionCode: Int,
    private val listener: Listener
) {
    fun interface Listener {
        fun onUpdateState(state: UpdateState)
    }

    private companion object {
        const val PREFS = "update_prefs"
        const val KEY_LAST_CHECK = "last_check_ms"
        const val KEY_FAILURES = "failures"
        const val KEY_NEXT_RETRY = "next_retry_ms"
        const val INTERVAL_MS = 24L * 60 * 60 * 1000
        const val RETRY_BASE_MS = 15L * 60 * 1000
        const val RETRY_MAX_MS = 6L * 60 * 60 * 1000
        const val STALE_CACHE_MS = 7L * 24 * 60 * 60 * 1000
        const val PROGRESS_STEP = 1024L * 1024
        val worker = Executors.newSingleThreadExecutor { job ->
            Thread(job, "shici-update").apply { isDaemon = true }
        }
        const val KEY_RESULT_KIND = "result_kind"
        const val KEY_RESULT_TAG = "result_tag"
        const val KEY_RESULT_ASSET = "result_asset"
        const val KEY_RESULT_SIZE = "result_size"
        const val KEY_RESULT_SHA = "result_sha"
    }

    private val context: Context = activity.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0)
    @Volatile private var started = false
    @Volatile private var closed = false
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var state: UpdateState = UpdateState.Idle
        private set

    /** Called from the Activity's onStart: prunes stale cache and checks at most once per interval. */
    fun onForeground() {
        if (closed) return
        started = true
        if (channel == null) {
            publishNow(UpdateState.Disabled)
            return
        }
        pruneCache()
        if (state is UpdateState.Checking || state is UpdateState.Downloading ||
            state is UpdateState.Ready || state is UpdateState.InstallBlocked ||
            state is UpdateState.InstallerOpened || state is UpdateState.InstallFailed) return
        if (intervalDue(System.currentTimeMillis())) {
            launchCheck(manual = false)
        } else if (state is UpdateState.Idle) {
            // A rotated/relaunched Activity shows the last decisive result instead of a fresh
            // spinner: no extra request, and no silent loss of an available update.
            restoreOutcome()
        }
    }

    /** Called from the Activity's onStop: no late result may reach UI, and transfers are cancelled. */
    fun onBackground() {
        started = false
        generation.incrementAndGet()
        // The lifecycle just cancelled a transfer/check, so its "in progress" state must not stick:
        // a cancelled download goes back to offerable, a cancelled check back to idle.
        when (val current = state) {
            is UpdateState.Downloading -> state = UpdateState.Available(current.release)
            UpdateState.Checking -> state = UpdateState.Idle
            else -> Unit
        }
    }

    fun close() {
        closed = true
        onBackground()
    }

    /** Explicit "检查更新" tap: bypasses the interval but never doubles an in-flight request. */
    fun checkNow() {
        if (closed || !started) return
        if (channel == null) {
            publishNow(UpdateState.Disabled)
            return
        }
        if (state is UpdateState.Checking || state is UpdateState.Downloading) return
        launchCheck(manual = true)
    }

    /** Explicit download tap. Only valid right after an [UpdateState.Available] result. */
    fun download() {
        val channel = channel ?: return
        if (closed || !started) return
        val release = (state as? UpdateState.Available)?.release ?: return
        val token = generation.incrementAndGet()
        publishNow(UpdateState.Downloading(release, 0, release.sizeBytes))
        worker.execute {
            if (!isCurrent(token)) return@execute
            try {
                val file = performDownload(release, channel, token)
                main.post {
                    if (isCurrent(token)) setState(UpdateState.Ready(release, file))
                    else worker.execute { file.delete() }
                }
            } catch (failure: Exception) {
                deliver(token, downloadFailure(release, failure))
            }
        }
    }

    /** Explicit install tap on an already verified file. Never installs without the user. */
    fun install() {
        if (closed || !started) return
        val current = state
        val release: UpdateRelease
        val file: File
        when (current) {
            is UpdateState.Ready -> { release = current.release; file = current.file }
            is UpdateState.InstallBlocked -> { release = current.release; file = current.file }
            is UpdateState.InstallFailed -> { release = current.release; file = current.file }
            is UpdateState.InstallerOpened -> { release = current.release; file = current.file }
            else -> return
        }
        if (!file.isFile) {
            publishNow(UpdateState.InstallFailed(release, file, "安装包已被清理，请重新下载"))
            return
        }
        try {
            if (!UpdateInstaller.canInstall(context)) {
                publishNow(UpdateState.InstallBlocked(release, file))
                return
            }
            val uri = UpdateInstaller.contentUri(context, file)
            activity.startActivity(UpdateInstaller.installerIntent(context, uri))
            publishNow(UpdateState.InstallerOpened(release, file))
        } catch (failure: Exception) {
            publishNow(
                UpdateState.InstallFailed(
                    release, file, "无法启动系统安装器：${failure.message ?: "未知原因"}"
                )
            )
        }
    }

    /** "打开未知来源设置" tap; the user must come back and tap install again. */
    fun openUnknownSourcesSettings() {
        if (closed || !started) return
        val current = state as? UpdateState.InstallBlocked ?: return
        try {
            activity.startActivity(UpdateInstaller.unknownSourcesIntent(context))
        } catch (failure: Exception) {
            publishNow(
                UpdateState.InstallBlocked(
                    current.release, current.file,
                    "无法打开系统设置，请在系统设置中允许本应用安装应用后返回重试"
                )
            )
        }
    }

    /** Cancels the one in-flight download; the partial file is deleted and the release stays offerable. */
    fun cancelDownload() {
        if (closed || !started) return
        val current = state as? UpdateState.Downloading ?: return
        generation.incrementAndGet()
        publishNow(UpdateState.Available(current.release))
    }

    // ---- checking --------------------------------------------------------------------------

    private fun intervalDue(now: Long): Boolean {
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (prefs.getInt(KEY_FAILURES, 0) > 0) {
            return prefs.getLong(KEY_NEXT_RETRY, 0L) - now !in 1..RETRY_MAX_MS
        }
        return last <= 0L || now - last !in 0 until INTERVAL_MS
    }

    private fun launchCheck(manual: Boolean) {
        val channel = channel ?: return
        val token = generation.incrementAndGet()
        val prior = state
        publishNow(UpdateState.Checking)
        worker.execute {
            if (!isCurrent(token)) return@execute
            val outcome = try {
                val body = UpdateHttp.getApi(ReleaseManifest.MAX_BYTES) { !isCurrent(token) }
                Result.success(
                    UpdatePolicy.decide(ReleaseManifest.parse(body), channel, installedVersionCode)
                )
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            main.post {
                if (!isCurrent(token)) return@post
                deliverCheck(outcome, manual, prior)
            }
        }
    }

    private fun deliverCheck(outcome: Result<UpdateDecision>, manual: Boolean, prior: UpdateState) {
        outcome.onSuccess { decision ->
            when (decision) {
                is UpdateDecision.Available -> {
                    recordSuccess(decision)
                    setState(UpdateState.Available(decision.release))
                }
                UpdateDecision.UpToDate -> {
                    recordSuccess(decision)
                    setState(UpdateState.UpToDate)
                }
                UpdateDecision.NoRelease -> {
                    recordSuccess(decision)
                    setState(UpdateState.NoRelease)
                }
                is UpdateDecision.Ambiguous -> {
                    clearCachedOutcome()
                    recordFailure()
                    setState(
                        UpdateState.Failure(
                            UpdateFailureKind.METADATA,
                            "发布信息有歧义（${decision.tags.joinToString("、")}），已停止更新检查"
                        )
                    )
                }
                is UpdateDecision.InvalidMetadata -> {
                    clearCachedOutcome()
                    recordFailure()
                    setState(
                        UpdateState.Failure(
                            UpdateFailureKind.METADATA,
                            "最新发布不可用：${decision.detail}"
                        )
                    )
                }
            }
        }.onFailure { failure ->
            recordFailure()
            // A transient background failure never overwrites a result the user can still act on.
            val kind = checkFailureKind(failure)
            if (kind == UpdateFailureKind.METADATA) clearCachedOutcome()
            if (!manual && prior is UpdateState.Available &&
                (kind == UpdateFailureKind.NETWORK || kind == UpdateFailureKind.RATE_LIMITED)) {
                setState(prior)
                return@onFailure
            }
            setState(UpdateState.Failure(kind, checkFailureMessage(kind, failure)))
        }
    }

    private fun checkFailureKind(failure: Throwable): UpdateFailureKind = when (failure) {
        is UpdateHttpException -> when (failure.kind) {
            UpdateHttpException.Kind.NETWORK, UpdateHttpException.Kind.HTTP -> UpdateFailureKind.NETWORK
            UpdateHttpException.Kind.RATE_LIMITED -> UpdateFailureKind.RATE_LIMITED
            UpdateHttpException.Kind.REDIRECT, UpdateHttpException.Kind.LIMIT,
            UpdateHttpException.Kind.VERIFY -> UpdateFailureKind.METADATA
            UpdateHttpException.Kind.CANCELLED -> UpdateFailureKind.CANCELLED
        }
        is UpdateJson.ParseException -> UpdateFailureKind.METADATA
        is java.io.IOException -> UpdateFailureKind.NETWORK
        else -> UpdateFailureKind.METADATA
    }

    private fun checkFailureMessage(kind: UpdateFailureKind, failure: Throwable): String =
        when (kind) {
            UpdateFailureKind.RATE_LIMITED ->
                "GitHub 暂时限制了更新请求，请稍后再试（这并不表示已是最新）"
            UpdateFailureKind.NETWORK ->
                "网络不可用或连接失败：${failure.message ?: "未知原因"}"
            UpdateFailureKind.METADATA ->
                "发布信息无法读取或不合规：${failure.message ?: "未知原因"}"
            UpdateFailureKind.CANCELLED -> "检查已取消"
            UpdateFailureKind.STORAGE -> "本机存储不可用"
            UpdateFailureKind.UNSUPPORTED -> "本构建未配置更新渠道"
        }

    // ---- downloading -----------------------------------------------------------------------

    private fun performDownload(release: UpdateRelease, channel: UpdateChannel, token: Long): File {
        val root = File(context.cacheDir, "updates")
        val staging = File(root, "staging").apply { mkdirs() }
        val verified = File(root, "verified").apply { mkdirs() }
        val part = File.createTempFile("update-", ".apk", staging)
        var reported = 0L
        fun ensureCurrent() {
            if (!isCurrent(token)) throw UpdateHttpException(UpdateHttpException.Kind.CANCELLED, "下载已取消")
        }
        try {
            UpdateHttp.download(
                release = release,
                destination = part,
                isCancelled = { !isCurrent(token) },
                onProgress = { done ->
                    if (done - reported >= PROGRESS_STEP || done == release.sizeBytes) {
                        reported = done
                        main.post {
                            if (isCurrent(token)) setState(UpdateState.Downloading(release, done, release.sizeBytes))
                        }
                    }
                }
            )
            ensureCurrent()
            val problem = ApkVerifier.verify(context, part, release.versionCode, release.versionName, channel)
            ensureCurrent()
            if (problem != null) throw UpdateHttpException(UpdateHttpException.Kind.VERIFY, problem)
            // Unique names keep a new download from replacing a file the installer may still read.
            val target = File(verified, part.name)
            if (!part.renameTo(target)) throw java.io.IOException("无法保存已校验的安装包")
            return target
        } finally {
            part.delete()
        }
    }

    private fun downloadFailure(release: UpdateRelease, failure: Throwable): UpdateState {
        if (failure is UpdateHttpException && failure.kind == UpdateHttpException.Kind.CANCELLED) {
            // A cancelled download keeps the release offerable instead of dropping it.
            return UpdateState.Available(release)
        }
        val kind = when (failure) {
            is UpdateHttpException -> when (failure.kind) {
                UpdateHttpException.Kind.NETWORK, UpdateHttpException.Kind.HTTP ->
                    UpdateFailureKind.NETWORK
                UpdateHttpException.Kind.RATE_LIMITED -> UpdateFailureKind.RATE_LIMITED
                else -> UpdateFailureKind.METADATA
            }
            is java.io.IOException -> UpdateFailureKind.STORAGE
            else -> UpdateFailureKind.METADATA
        }
        val message = when (failure) {
            is UpdateHttpException -> failure.message ?: "下载失败"
            is java.io.IOException -> "写入本机缓存失败：${failure.message ?: "未知原因"}"
            else -> "下载失败：${failure.message ?: "未知原因"}"
        }
        // A download failure never pretends a file exists; only a verified file can be installed.
        return UpdateState.Failure(kind, "版本 ${release.versionName} 下载失败：$message")
    }

    // ---- bookkeeping -----------------------------------------------------------------------

    private fun pruneCache() {
        val now = System.currentTimeMillis()
        worker.execute {
            val root = File(context.cacheDir, "updates")
            for (folder in listOf("staging", "verified")) {
                File(root, folder).listFiles()?.forEach { file ->
                    if (now - file.lastModified() > STALE_CACHE_MS) file.delete()
                }
            }
        }
    }

    /**
     * Persists the decisive result so a rotated/relaunched Activity can show it without a second
     * request. Only the fields needed to rebuild a candidate are stored; the download URL is
     * regenerated from tag + asset name and re-validated by [UpdatePolicy] on restore.
     */
    private fun recordSuccess(decision: UpdateDecision) {
        val editor = prefs.edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .putInt(KEY_FAILURES, 0)
            .putLong(KEY_NEXT_RETRY, 0L)
        when (decision) {
            is UpdateDecision.Available -> editor
                .putString(KEY_RESULT_KIND, "available")
                .putString(KEY_RESULT_TAG, decision.release.tag)
                .putString(KEY_RESULT_ASSET, decision.release.assetName)
                .putLong(KEY_RESULT_SIZE, decision.release.sizeBytes)
                .putString(KEY_RESULT_SHA, decision.release.sha256)
            UpdateDecision.UpToDate -> editor
                .putString(KEY_RESULT_KIND, "uptodate")
                .remove(KEY_RESULT_TAG)
            UpdateDecision.NoRelease -> editor
                .putString(KEY_RESULT_KIND, "norelease")
                .remove(KEY_RESULT_TAG)
            else -> return
        }
        editor.apply()
    }

    /** Restores the last decisive result offline. A stale record simply degrades to no state. */
    private fun restoreOutcome() {
        val channel = channel ?: return
        when (prefs.getString(KEY_RESULT_KIND, null)) {
            "uptodate" -> publishNow(UpdateState.UpToDate)
            "norelease" -> publishNow(UpdateState.NoRelease)
            "available" -> {
                val tag = prefs.getString(KEY_RESULT_TAG, null) ?: return
                val asset = prefs.getString(KEY_RESULT_ASSET, null) ?: return
                val sha = prefs.getString(KEY_RESULT_SHA, null) ?: return
                val size = prefs.getLong(KEY_RESULT_SIZE, -1L)
                if (size <= 0L) return
                // Rebuild the one candidate and let UpdatePolicy re-check every invariant (channel
                // tag, canonical asset name, digest shape, size cap, exact URL, newer-than-installed).
                val candidate = ReleaseEntry(
                    tag = tag,
                    draft = false,
                    assets = listOf(
                        ReleaseAsset(
                            name = asset,
                            size = size,
                            digest = "sha256:$sha",
                            state = "uploaded",
                            downloadUrl = UpdatePolicy.downloadUrl(tag, asset)
                        )
                    )
                )
                when (val decision = UpdatePolicy.decide(listOf(candidate), channel, installedVersionCode)) {
                    is UpdateDecision.Available -> publishNow(UpdateState.Available(decision.release))
                    UpdateDecision.UpToDate -> publishNow(UpdateState.UpToDate)
                    else -> Unit
                }
            }
        }
    }

    private fun clearCachedOutcome() {
        prefs.edit().remove(KEY_RESULT_KIND).apply()
    }

    private fun recordFailure() {
        val failures = (prefs.getInt(KEY_FAILURES, 0).coerceIn(0, 5) + 1)
        val factor = 1L shl (failures - 1)
        val delay = (RETRY_BASE_MS * factor).coerceAtMost(RETRY_MAX_MS)
        prefs.edit()
            .putInt(KEY_FAILURES, failures)
            .putLong(KEY_NEXT_RETRY, System.currentTimeMillis() + delay)
            .apply()
    }

    private fun isCurrent(token: Long): Boolean =
        !closed && started && generation.get() == token

    /** Main-thread-only state publication for UI-initiated transitions. */
    private fun publishNow(next: UpdateState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { publishNow(next) }
            return
        }
        if (closed) return
        setState(next)
    }

    /** Main-thread-only delivery of a background result that must still match its generation. */
    private fun deliver(token: Long, next: UpdateState) {
        main.post {
            if (!isCurrent(token)) return@post
            setState(next)
        }
    }

    private fun setState(next: UpdateState) {
        state = next
        if (started && !closed) listener.onUpdateState(next)
    }
}
