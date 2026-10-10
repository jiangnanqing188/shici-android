package com.shici.app

import android.app.Application
import com.shici.app.data.LearningRepository
import com.shici.app.core.RawKajwebDictionary
import org.json.JSONObject
import java.util.concurrent.Executors

class ShiciApplication : Application() {
    val worker = Executors.newSingleThreadExecutor()
    val repository by lazy { LearningRepository(this) }
    // Access only from worker: cache both a fully validated dictionary and an explicit load failure.
    val dictionary: Result<RawKajwebDictionary> by lazy {
        runCatching {
            val json = assets.open(RawKajwebDictionary.MANIFEST_ASSET).use {
                JSONObject(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(it.readBytes())).toString())
            }
            require(json.getString("format") == RawKajwebDictionary.FORMAT &&
                json.getInt("format_version") == RawKajwebDictionary.FORMAT_VERSION &&
                json.getString("content_review_status") == "unreviewed" &&
                !json.getBoolean("syllabus_verified")) { "词库格式或未核对声明不匹配" }
            val manifest = RawKajwebDictionary.Manifest(json.getString("asset_sha256"),
                json.getInt("entry_count"), json.getInt("alias_count"),
                json.getString("source"), json.getString("version"))
            require(manifest.entryCount == RawKajwebDictionary.EXPECTED_ENTRY_COUNT) { "词库不是固定原始词头集合" }
            RawKajwebDictionary.load(assets.open(RawKajwebDictionary.ASSET), manifest)
        }
    }
}
