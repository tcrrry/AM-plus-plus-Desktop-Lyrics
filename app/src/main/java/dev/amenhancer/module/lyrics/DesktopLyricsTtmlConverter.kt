package dev.amenhancer.module.lyrics

import com.tcrrry.desktoplyrics.DirectLyricsRepository
import java.util.Locale

/** Converts Desktop Lyrics' LRC/YRC result into the format consumed by Apple's parser. */
internal object DesktopLyricsTtmlConverter {
    private data class Word(val start: Long, val end: Long, val text: String)
    private data class Line(val start: Long, val end: Long, val words: List<Word>)
    private data class TimedText(val start: Long, val text: String)

    private val wordLine = Regex("^\\[(\\d+),(\\d+)\\](.*)$")
    private val wordToken = Regex("\\((\\d+),(\\d+)(?:,\\d+)?\\)")
    private val lrcStamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")

    fun convert(
        result: DirectLyricsRepository.Result,
        expectedDurationMs: Long = 0L,
        offsetMs: Int = 0,
    ): String? {
        val ordinary = parseLrc(result.lyrics)
        val words = parseWords(result.wordLyrics)
        val lines = if (words.isNotEmpty()) words else ordinary.mapIndexed { index, line ->
            val next = ordinary.getOrNull(index + 1)?.start
                ?: expectedDurationMs.takeIf { it > line.start }
                ?: line.start + 4_000L
            val end = maxOf(line.start + 400L, minOf(next, line.start + 10_000L))
            Line(line.start, end, listOf(Word(line.start, end, line.text)))
        }
        if (lines.isEmpty()) return null
        val translations = parseLrc(result.translatedLyrics)
        val translated = lines.map { line ->
            val closest = translations.minByOrNull { kotlin.math.abs(it.start - line.start) }
                ?.takeIf { kotlin.math.abs(it.start - line.start) <= 1_200L }
            closest?.text?.takeIf(String::isNotBlank)
        }
        val hasTranslation = translated.any { !it.isNullOrBlank() }
        val ttml = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<tt xmlns=\"http://www.w3.org/ns/ttml\" ")
            append("xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\" ")
            append("itunes:timing=\"Word\"")
            if (hasTranslation) append(" xml:lang=\"ko\"")
            append('>')
            if (hasTranslation) {
                append("<head><metadata><iTunesMetadata xmlns=\"http://music.apple.com/lyric-ttml-internal\">")
                append("<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\">")
                translated.forEachIndexed { index, value ->
                    append("<text for=\"L${index + 1}\">${escape(value ?: " ")}</text>")
                }
                append("</translation></translations></iTunesMetadata></metadata></head>")
            }
            append("<body><div>")
            lines.forEachIndexed { index, line ->
                val lineBegin = (line.start - offsetMs).coerceAtLeast(0L)
                val lineEnd = (line.end - offsetMs).coerceAtLeast(lineBegin + 1L)
                append("<p begin=\"${stamp(lineBegin)}\" end=\"${stamp(lineEnd)}\" itunes:key=\"L${index + 1}\">")
                line.words.forEach { word ->
                    val wordBegin = (word.start - offsetMs).coerceAtLeast(lineBegin)
                    val wordEnd = (word.end - offsetMs).coerceAtLeast(wordBegin + 1L)
                    append("<span begin=\"${stamp(wordBegin)}\" end=\"${stamp(wordEnd)}\">${escape(word.text)}</span>")
                }
                append("</p>")
            }
            append("</div></body></tt>")
        }
        return ttml.takeIf(TtmlInputPolicy::isAcceptable)
    }

    private fun parseWords(raw: String): List<Line> = raw.lineSequence().mapNotNull { text ->
        val match = wordLine.matchEntire(text.trim()) ?: return@mapNotNull null
        val start = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val duration = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
        val body = match.groupValues[3]
        val markers = wordToken.findAll(body).toList()
        val words = markers.mapIndexedNotNull { index, marker ->
            val begin = marker.groupValues[1].toLongOrNull() ?: return@mapIndexedNotNull null
            val length = marker.groupValues[2].toLongOrNull() ?: return@mapIndexedNotNull null
            val value = body.substring(marker.range.last + 1,
                markers.getOrNull(index + 1)?.range?.first ?: body.length)
            if (value.isBlank() || length <= 0L) null else Word(begin, begin + length, value)
        }
        if (words.isEmpty()) null else Line(start, maxOf(start + duration, words.maxOf(Word::end)), words)
    }.sortedBy(Line::start).take(4096).toList()

    private fun parseLrc(raw: String): List<TimedText> = raw.lineSequence().flatMap { line ->
        val stamps = lrcStamp.findAll(line).toList()
        val text = lrcStamp.replace(line, "").trim()
        if (text.isBlank() || stamps.isEmpty()) emptySequence() else stamps.asSequence().map { stamp ->
            val fraction = stamp.groupValues[3].takeIf(String::isNotEmpty)
                ?.let { ("0.$it".toDouble() * 1000).toLong() } ?: 0L
            TimedText(stamp.groupValues[1].toLong() * 60_000L +
                stamp.groupValues[2].toLong() * 1_000L + fraction, text)
        }
    }.sortedBy(TimedText::start).take(4096).toList()

    private fun stamp(ms: Long): String = String.format(Locale.US, "%d:%02d.%03d",
        ms.coerceAtLeast(0L) / 60_000L, ms.coerceAtLeast(0L) / 1_000L % 60L,
        ms.coerceAtLeast(0L) % 1_000L)

    private fun escape(value: String): String = value.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
