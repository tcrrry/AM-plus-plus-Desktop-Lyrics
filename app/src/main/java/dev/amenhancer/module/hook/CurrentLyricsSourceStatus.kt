package dev.amenhancer.module.hook

import android.content.Context
import android.os.Process

/** The source actually handed to Apple's lyric view for the current song. */
internal object CurrentLyricsSourceStatus {
    private const val PREFS = "ampp-current-lyrics-source"
    private val SOURCES = setOf("QQ音乐", "网易云音乐", "LRCLIB")
    @Volatile private var refreshHandler: ((Long) -> Boolean)? = null

    fun installRefreshHandler(handler: (Long) -> Boolean) { refreshHandler = handler }
    fun refresh(id: Long): Boolean = refreshHandler?.invoke(id) ?: false

    fun selectedSource(context: Context, id: Long): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("selected_$id", null)?.takeIf { it in SOURCES }

    fun resetMatching(context: Context, id: Long) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("selected_$id")
        SOURCES.forEach { edit.remove("excluded_${id}_$it").remove("version_${id}_$it") }
        edit.apply()
    }

    fun selectSource(context: Context, id: Long, source: String?) {
        if (id <= 0L || (source != null && source !in SOURCES)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("selected_$id", source).apply()
    }

    fun offsetMs(context: Context, id: Long, source: String): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("offset_${id}_$source", 0)

    fun setOffsetMs(context: Context, id: Long, source: String, value: Int) {
        if (id <= 0L || source.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("offset_${id}_$source", value.coerceIn(-5_000, 5_000)).apply()
    }

    fun excludedRecords(context: Context, id: Long, source: String): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet("excluded_${id}_$source", emptySet())?.toSet().orEmpty()

    fun excludeCurrentRecord(context: Context, id: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val source = prefs.getString("applied_source", null)?.substringAfter(':') ?: return false
        if (source !in SOURCES || prefs.getLong("applied_id", 0L) != id) return false
        val record = prefs.getString("record_${id}_$source", null)?.takeIf(String::isNotBlank)
            ?: return false
        prefs.edit().putStringSet(
            "excluded_${id}_$source", (excludedRecords(context, id, source) + record).toSet(),
        ).putString("selected_$id", source).remove("version_${id}_$source").apply()
        return true
    }

    fun rememberRecord(context: Context, id: Long, source: String, recordId: String) {
        if (id <= 0L || source !in SOURCES || recordId.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("record_${id}_$source", recordId).apply()
    }

    fun rememberTranslationStatus(context: Context, id: Long, status: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("translation_status_$id", status.take(180)).apply()
    }

    fun translationStatus(context: Context, id: Long): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("translation_status_$id", null).orEmpty()

    fun appliedSource(context: Context, id: Long): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("applied_source", null)
            ?.takeIf { prefs.getLong("applied_id", 0L) == id && prefs.getInt("applied_pid", 0) == Process.myPid() }
    }

    fun rememberCandidate(context: Context, id: Long, source: String, hasTranslation: Boolean) {
        if (id <= 0L || source.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("source_$id", source)
            .putBoolean("translation_$id", hasTranslation)
            .apply()
    }

    fun recordApplied(context: Context, id: Long, manual: Boolean) {
        if (id <= 0L) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val source = if (manual) "manual" else prefs.getString("source_$id", null) ?: "automatic-cache"
        prefs.edit()
            .putLong("applied_id", id)
            .putInt("applied_pid", Process.myPid())
            .putString("applied_source", source)
            .putBoolean("applied_translation", !manual && prefs.getBoolean("translation_$id", false))
            .apply()
    }

    fun description(context: Context, currentId: Long?): String {
        if (currentId == null || currentId <= 0L) return "暂无正在播放的歌曲"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong("applied_id", 0L) != currentId ||
            prefs.getInt("applied_pid", 0) != Process.myPid()
        ) return "当前歌曲尚未装入替换歌词"
        val source = prefs.getString("applied_source", null).orEmpty()
        val translated = prefs.getBoolean("applied_translation", false)
        val name = when {
            source.startsWith("desktop-lyrics:") -> "我的歌词源 · ${source.substringAfter(':')}"
            source == "manual" -> "手动指定的歌词"
            source == "automatic-cache" -> "自动歌词缓存（来源未记录）"
            source.isBlank() -> "来源未记录"
            else -> source
        }
        val translationKind = when {
            !translated -> ""
            translationStatus(context, currentId).startsWith("API 翻译已补译") -> " · 含 API 补译"
            translationStatus(context, currentId).startsWith("离线机翻已补译") -> " · 含离线机翻"
            else -> " · 含来源译文"
        }
        return name + translationKind
    }
}
