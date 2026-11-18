package com.shici.app.update

/**
 * One attachment of a GitHub release, reduced to the fields the install decision actually uses.
 *
 * @param digest the API's "digest" string, expected to be "sha256:<64 lowercase hex>"; null when
 *   the release document carried none.
 */
data class ReleaseAsset(
    val name: String,
    val size: Long,
    val digest: String?,
    val state: String?,
    val downloadUrl: String?
)

/** A release row; prereleases are included, but drafts are never update candidates. */
data class ReleaseEntry(val tag: String, val draft: Boolean, val assets: List<ReleaseAsset>)

/**
 * Parses the GitHub releases list body. Pure and host-testable: it touches no Android API and no
 * network. A malformed document throws [UpdateJson.ParseException] instead of silently yielding an
 * empty list, because "cannot read the release list" must never look like "already up to date".
 */
object ReleaseManifest {
    /** Hard cap for the release-list response body. The real document is a few tens of KiB. */
    const val MAX_BYTES = 2 * 1024 * 1024
    /** Hard cap for a single asset entry string field (names and URLs are short). */
    private const val MAX_FIELD_CHARS = 2048

    fun parse(body: String): List<ReleaseEntry> {
        require(body.length <= MAX_BYTES) { "发布列表响应超过大小上限" }
        val root = UpdateJson.parse(body).asJsonArray()
            ?: throw UpdateJson.ParseException("发布列表不是 JSON 数组")
        val releases = ArrayList<ReleaseEntry>(root.size)
        for (element in root) {
            val json = element.asJsonObject() ?: invalid()
            val tag = json["tag_name"].asJsonString()?.takeIf { it.length in 1..MAX_FIELD_CHARS } ?: invalid()
            val draft = json["draft"].asJsonBoolean() ?: invalid()
            if (!json.containsKey("published_at")) invalid()
            if (draft || json["published_at"] == null) continue
            val published = json["published_at"].asJsonString() ?: invalid()
            try {
                java.time.Instant.parse(published)
            } catch (_: java.time.format.DateTimeParseException) { invalid() }
            val assets = json["assets"].asJsonArray() ?: invalid()
            releases.add(ReleaseEntry(tag, false, assets.map(::parseAsset)))
        }
        return releases
    }

    private fun invalid(): Nothing = throw UpdateJson.ParseException("发布列表字段缺失或类型无效")

    private fun parseAsset(element: Any?): ReleaseAsset {
        val json = element.asJsonObject() ?: invalid()
        val name = json["name"].asJsonString()?.takeIf { it.length in 1..MAX_FIELD_CHARS } ?: invalid()
        return ReleaseAsset(
            name = name,
            size = (json["size"] as? Long) ?: -1L,
            digest = json["digest"].asJsonString()?.takeIf { it.length <= MAX_FIELD_CHARS },
            state = json["state"].asJsonString()?.takeIf { it.length <= 32 },
            downloadUrl = json["browser_download_url"].asJsonString()
                ?.takeIf { it.length in 1..MAX_FIELD_CHARS }
        )
    }
}
