package dev.amenhancer.module.lyrics

import com.tcrrry.desktoplyrics.DirectLyricsRepository
import dev.amenhancer.module.hook.TtmlTimingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopLyricsTtmlConverterTest {
    @Test fun positiveOffsetAdvancesBothLineAndWordTimes() {
        val result = DirectLyricsRepository.Result(lyrics = "[00:01.000]one")
        val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(result, 3_000L, 100))
        assertTrue(ttml.contains("<p begin=\"0:00.900\" end=\"0:02.900\""))
        assertTrue(ttml.contains("<span begin=\"0:00.900\" end=\"0:02.900\">one</span>"))
    }
    @Test fun convertsWordTimingAndOfficialTranslation() {
        val result = DirectLyricsRepository.Result(
            lyrics = "[00:01.000]A & B",
            wordLyrics = "[1000,900](1000,300)A &(1300,600) B",
            translatedLyrics = "[00:01.000]甲 < 乙",
        )
        val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(result))
        assertTrue(ttml.contains("itunes:timing=\"Word\""))
        assertTrue(ttml.contains("<span begin=\"0:01.000\" end=\"0:01.300\">A &amp;</span>"))
        assertTrue(ttml.contains("<text for=\"L1\">甲 &lt; 乙</text>"))
        assertEquals(TtmlTimingMode.WORD, dev.amenhancer.module.hook.TtmlTimingPolicy.modeOf(ttml))
    }

    @Test fun lineTimingHasSingleTimedSpan() {
        val result = DirectLyricsRepository.Result(lyrics = "[00:01.000]one\n[00:03.000]two")
        val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(result, 5_000L))
        assertTrue(ttml.contains("<p begin=\"0:01.000\" end=\"0:03.000\""))
        assertTrue(ttml.contains("<span begin=\"0:03.000\" end=\"0:05.000\">two</span>"))
    }

    @Test fun missingTranslationKeepsItsKeyedPlaceholder() {
        val result = DirectLyricsRepository.Result(
            lyrics = "[00:01.000]one\n[00:03.000]two\n[00:05.000]three",
            translatedLyrics = "[00:01.000]一\n[00:05.000]三",
        )
        val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(result, 7_000L))
        assertTrue(ttml.contains("<text for=\"L1\">一</text><text for=\"L2\"> </text><text for=\"L3\">三</text>"))
    }
}
