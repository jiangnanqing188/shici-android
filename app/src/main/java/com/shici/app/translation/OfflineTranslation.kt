package com.shici.app.translation

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/** Result of an explicit, local-only model-state query. Nothing is downloaded here. */
sealed class LocalModelState {
    object Ready : LocalModelState()
    object Missing : LocalModelState()
    data class Unavailable(val message: String) : LocalModelState()
}

sealed class ModelDownloadResult {
    object Ready : ModelDownloadResult()
    data class Failed(val message: String) : ModelDownloadResult()
}

sealed class LocalTranslationResult {
    data class Translated(val text: String) : LocalTranslationResult()
    data class Failed(val message: String) : LocalTranslationResult()
}

/**
 * The only operations the translation screen needs from the SDK. Implementations must deliver
 * every callback on the main thread and must not deliver anything after [close]. Keeping this
 * boundary small lets [TranslationSession] own all state/ownership decisions without Android
 * framework types, and makes it impossible for the UI to trigger a hidden download or an online
 * fallback: there is deliberately no "translate or download" operation.
 */
interface TranslationEngine {
    /** Queries the already-downloaded local model. Never starts a download. */
    fun checkModel(onResult: (LocalModelState) -> Unit)

    /** Downloads only after the user's explicit confirmation. Wi-Fi is required. */
    fun downloadModel(onResult: (ModelDownloadResult) -> Unit)

    /** Translates entirely on the device. The caller must have confirmed the model is ready. */
    fun translate(text: String, onResult: (LocalTranslationResult) -> Unit)

    /** Releases the ML Kit client. Later callbacks from in-flight tasks are suppressed. */
    fun close()
}

/**
 * Google ML Kit on-device English -> Chinese translation.
 *
 * Privacy boundary: the text handed to [translate] is processed by the on-device ML Kit model and
 * is not uploaded by this application. ML Kit itself may contact Google to update models and to
 * send performance/usage metrics, so the app is not "completely offline". Model availability
 * depends on the device (Google Play services) and the network; this class never falls back to an
 * online translator, a paid service or a mobile-data download.
 *
 * Threading: callbacks are posted to the main looper. [close] is thread-safe and suppresses every
 * callback that has not yet run, including tasks that fail because the client was closed.
 */
class OfflineTranslation(@Suppress("UNUSED_PARAMETER") context: Context) : TranslationEngine {
    private val main = Handler(Looper.getMainLooper())
    private val sourceLanguage = TranslateLanguage.ENGLISH
    private val targetLanguage = TranslateLanguage.CHINESE
    private val options = TranslatorOptions.Builder()
        .setSourceLanguage(sourceLanguage)
        .setTargetLanguage(targetLanguage)
        .build()

    private var setupError: String? = null
    private val translator: Translator? = try {
        Translation.getClient(options)
    } catch (e: Exception) {
        setupError = describe(e)
        null
    }
    private val remoteModel: TranslateRemoteModel? = try {
        // The 17.0.3 public Builder takes a single supported language, not a language-pair string.
        // English is the built-in source side of this translator, so the downloadable model that
        // TranslatorOptions(ENGLISH -> CHINESE) needs is the Chinese model. This identifies exactly
        // that model for state checks; it never starts a download.
        TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build()
    } catch (e: Exception) {
        if (setupError == null) setupError = describe(e)
        null
    }

    @Volatile private var closed = false

    override fun checkModel(onResult: (LocalModelState) -> Unit) {
        if (closed) return
        if (translator == null) {
            deliver { onResult(LocalModelState.Unavailable(setupError ?: "本机翻译组件不可用")) }
            return
        }
        val model = remoteModel
        if (model == null) {
            deliver { onResult(LocalModelState.Unavailable(setupError ?: "无法确认本机翻译模型")) }
            return
        }
        try {
            RemoteModelManager.getInstance().isModelDownloaded(model)
                .addOnSuccessListener { downloaded ->
                    deliver { onResult(if (downloaded) LocalModelState.Ready else LocalModelState.Missing) }
                }
                .addOnFailureListener { error ->
                    deliver { onResult(LocalModelState.Unavailable(describe(error))) }
                }
        } catch (e: Exception) {
            deliver { onResult(LocalModelState.Unavailable(describe(e))) }
        }
    }

    override fun downloadModel(onResult: (ModelDownloadResult) -> Unit) {
        if (closed) return
        val client = translator
        if (client == null) {
            deliver { onResult(ModelDownloadResult.Failed(setupError ?: "本机翻译组件不可用")) }
            return
        }
        try {
            // Wi-Fi only. Without Wi-Fi the SDK task waits instead of silently consuming mobile
            // data; the screen reports that waiting state honestly and lets the user retry/close.
            val conditions = DownloadConditions.Builder().requireWifi().build()
            client.downloadModelIfNeeded(conditions)
                .addOnSuccessListener { deliver { onResult(ModelDownloadResult.Ready) } }
                .addOnFailureListener { error ->
                    deliver { onResult(ModelDownloadResult.Failed(describe(error))) }
                }
        } catch (e: Exception) {
            deliver { onResult(ModelDownloadResult.Failed(describe(e))) }
        }
    }

    override fun translate(text: String, onResult: (LocalTranslationResult) -> Unit) {
        if (closed) return
        val client = translator
        if (client == null) {
            deliver { onResult(LocalTranslationResult.Failed(setupError ?: "本机翻译组件不可用")) }
            return
        }
        try {
            client.translate(text)
                .addOnSuccessListener { translated ->
                    deliver { onResult(LocalTranslationResult.Translated(translated)) }
                }
                .addOnFailureListener { error ->
                    deliver { onResult(LocalTranslationResult.Failed(describe(error))) }
                }
        } catch (e: Exception) {
            deliver { onResult(LocalTranslationResult.Failed(describe(e))) }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { translator?.close() }
    }

    private fun deliver(block: () -> Unit) {
        if (closed) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!closed) block()
        } else {
            main.post { if (!closed) block() }
        }
    }

    private fun describe(error: Throwable): String {
        val detail = error.message?.trim().orEmpty()
        return detail.ifEmpty { error.javaClass.simpleName }
    }
}
