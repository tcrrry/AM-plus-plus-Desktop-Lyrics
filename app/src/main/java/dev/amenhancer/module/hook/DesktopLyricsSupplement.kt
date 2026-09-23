package dev.amenhancer.module.hook

import android.content.Context
import com.tcrrry.desktoplyrics.DirectLyricsRepository
import com.tcrrry.desktoplyrics.SupplementTranslation
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Adds machine translation only where the selected provider has no translation. */
internal class DesktopLyricsSupplement(private val context: Context) {
    private data class TimedLine(val at: Long, val text: String)
    private val stamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")

    fun fill(result: DirectLyricsRepository.Result, appleMusicId: Long): DirectLyricsRepository.Result {
        val mode = context.getSharedPreferences("supplement_translation", Context.MODE_PRIVATE)
            .getString("mode", "off")
        if (mode !in setOf("offline", "api")) {
            CurrentLyricsSourceStatus.rememberTranslationStatus(context, appleMusicId, "补充翻译已关闭")
            return result
        }
        val original = lines(result.lyrics)
        if (original.isEmpty()) return result
        val official = lines(result.translatedLyrics)
        val missing = original.withIndex().filter { (_, line) ->
            official.none { kotlin.math.abs(it.at - line.at) <= 1_200L && it.text.isNotBlank() }
        }
        if (missing.isEmpty()) {
            CurrentLyricsSourceStatus.rememberTranslationStatus(context, appleMusicId, "已使用来源译文，无需补译")
            return result
        }
        val payload = JSONArray().apply {
            missing.forEach { (index, line) ->
                put(JSONObject().put("id", index).put("text", line.text))
            }
        }
        val generated = mutableMapOf<Int, String>()
        val attempt = runCatching {
            if (mode == "offline") EmbeddedMlKit.initialize(context)
            else runCatching { EmbeddedMlKit.initialize(context) }
            runBlocking {
                SupplementTranslation(context).translate(payload.toString()) { row ->
                    val id = row.optInt("id", -1)
                    val text = row.optString("text").trim().replace(Regex("\\s+"), " ")
                    if (id in original.indices && text.isNotBlank()) generated[id] = text
                }
            }
        }
        val status = when {
            generated.isNotEmpty() -> "${if (mode == "api") "API 翻译" else "离线机翻"}已补译 ${generated.size} 句"
            attempt.isFailure -> attempt.exceptionOrNull()?.message?.take(150).orEmpty().ifBlank { "翻译暂不可用" }
            else -> "当前歌曲无需补译或源语言为中文"
        }
        CurrentLyricsSourceStatus.rememberTranslationStatus(context, appleMusicId, status)
        if (generated.isEmpty()) return result
        val all = original.mapIndexedNotNull { index, line ->
            val officialLine = official.minByOrNull { kotlin.math.abs(it.at - line.at) }
                ?.takeIf { kotlin.math.abs(it.at - line.at) <= 1_200L }
            val text = officialLine?.text?.takeIf(String::isNotBlank) ?: generated[index]
            text?.let { TimedLine(line.at, it) }
        }
        return result.copy(translatedLyrics = all.joinToString("\n") { line ->
            val minute = line.at / 60_000L
            val second = line.at / 1_000L % 60L
            val millis = line.at % 1_000L
            String.format(Locale.US, "[%02d:%02d.%03d]%s", minute, second, millis, line.text)
        })
    }

    private fun lines(raw: String): List<TimedLine> = raw.lineSequence().flatMap { line ->
        val text = stamp.replace(line, "").trim()
        if (text.isBlank()) return@flatMap emptySequence()
        stamp.findAll(line).map { match ->
            val fraction = match.groupValues[3].takeIf(String::isNotBlank)
                ?.let { ("0.$it".toDouble() * 1000).toLong() } ?: 0L
            TimedLine(match.groupValues[1].toLong() * 60_000L +
                match.groupValues[2].toLong() * 1_000L + fraction, text)
        }
    }.take(1000).toList()
}
