package dev.amenhancer.module.hook

import com.tcrrry.desktoplyrics.DirectLyricsRepository
import android.content.Context
import dev.amenhancer.module.lyrics.DesktopLyricsTtmlConverter
import dev.amenhancer.module.model.CustomLyricsSources

internal data class DesktopLyricsTrack(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val appleMusicId: Long = 0L,
    val explicitSource: Boolean = false,
)

/** Runs the original Desktop Lyrics provider search off the Apple Music hook thread. */
internal class DesktopLyricsSource(
    private val context: Context,
    private val repository: DirectLyricsRepository = DirectLyricsRepository(),
) {
    private val supplement = DesktopLyricsSupplement(context)
    private val results = object : LinkedHashMap<String, DirectLyricsRepository.Result>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DirectLyricsRepository.Result>?): Boolean = size > 24
    }
    fun fetch(track: DesktopLyricsTrack): AutoLyricsCandidate? {
        if (track.title.isBlank() || track.artist.isBlank()) return null
        val selected = CurrentLyricsSourceStatus.selectedSource(context, track.appleMusicId)
        val excluded = selected?.let { CurrentLyricsSourceStatus.excludedRecords(context, track.appleMusicId, it) }.orEmpty()
        val key = listOf(track.appleMusicId, track.title, track.artist, track.album, track.durationMs, selected, excluded.sorted()).joinToString("|")
        val result = TcrrryLyricsHistory.selected(context, track.appleMusicId, selected) ?: results[key] ?: (if (selected == null) {
            repository.resolveLyrics(track.title, track.artist, track.album, track.durationMs)
        } else {
            repository.rematch(
                selected, track.title, track.artist, track.album, track.durationMs,
                excluded,
            ) ?: return null
        }).also { if (it.lyrics.isNotBlank()) results[key] = it }
        ModernXposedRuntime.log(
            "Desktop Lyrics provider result source=${result.source.ifBlank { "none" }} " +
                "score=${result.score} lines=${result.lyrics.lineSequence().count()} " +
                "word=${result.wordLyrics.isNotBlank()} translation=${result.translatedLyrics.isNotBlank()}",
        )
        CurrentLyricsSourceStatus.rememberRecord(
            context, track.appleMusicId, result.source, result.recordId,
        )
        TcrrryLyricsHistory.remember(context, track.appleMusicId, result)
        val offset = CurrentLyricsSourceStatus.offsetMs(context, track.appleMusicId, result.source)
        val complete = supplement.fill(result, track.appleMusicId)
        val ttml = DesktopLyricsTtmlConverter.convert(complete, track.durationMs, offset) ?: return null
        return AutoLyricsCandidate(
            source = "${CustomLyricsSources.DESKTOP_LYRICS}:${result.source}",
            ttml = ttml,
            displayName = "${track.title} - ${track.artist}",
        )
    }
}
