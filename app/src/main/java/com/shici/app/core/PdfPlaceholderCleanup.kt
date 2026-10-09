package com.shici.app.core

/** Only a confirmed empty result may be deleted; unknown metadata never means zero. */
object PdfPlaceholderCleanup {
    data class Metadata(val size: Long?, val supportsDelete: Boolean)
    enum class Result(private val detail: String) {
        NO_URI("系统未返回文件位置，未检查或删除文件"),
        DELETED_EMPTY("已删除本次返回的 0 字节占位文件"),
        KEPT_NONEMPTY("所选文件非空，已保留，未写入或删除其内容"),
        KEPT_UNKNOWN_SIZE("无法确认所选文件为空，已保留，请检查所选位置"),
        KEPT_UNSUPPORTED("所选文件为 0 字节，但保存位置不支持删除，已保留，请手动清理"),
        CHECK_FAILED("所选文件检查失败，未删除，请检查所选位置"),
        DELETE_FAILED("所选文件为 0 字节，但未能确认删除成功，请检查所选位置并手动清理");
        val message get() = "导出状态因重建丢失，本次导出无效，请重新导出；$detail"
    }

    fun <T : Any> clean(target: T?, inspect: (T) -> Metadata, delete: (T) -> Boolean): Result {
        if (target == null) return Result.NO_URI
        val metadata = try { inspect(target) } catch (_: Exception) { return Result.CHECK_FAILED }
        val size = metadata.size ?: return Result.KEPT_UNKNOWN_SIZE
        if (size < 0) return Result.KEPT_UNKNOWN_SIZE
        if (size > 0) return Result.KEPT_NONEMPTY
        if (!metadata.supportsDelete) return Result.KEPT_UNSUPPORTED
        return try {
            if (delete(target)) Result.DELETED_EMPTY else Result.DELETE_FAILED
        } catch (_: Exception) { Result.DELETE_FAILED }
    }
}
