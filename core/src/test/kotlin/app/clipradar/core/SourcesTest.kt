package app.clipradar.core

import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourcesTest {
    @Test
    fun twitchNicksAndLinks() {
        assertEquals("shroud", Sources.twitchChannel("shroud"))
        assertEquals("shroud", Sources.twitchChannel("@Shroud"))
        assertEquals("shroud", Sources.twitchChannel("https://www.twitch.tv/Shroud?sr=a"))
        assertEquals("shroud", Sources.twitchChannel("twitch.tv/shroud"))
        assertEquals("shroud", Sources.twitchChannel("https://m.twitch.tv/shroud/"))
        assertNull(Sources.twitchChannel("https://www.twitch.tv/videos/123456"))
        assertNull(Sources.twitchChannel("https://youtube.com/watch?v=abc"))
        assertNull(Sources.twitchChannel("плохой ник!"))
    }

    @Test
    fun kinds() {
        assertEquals(SourceKind.TWITCH_VOD, Sources.kind("https://www.twitch.tv/videos/123456"))
        assertEquals(SourceKind.TWITCH_CHANNEL, Sources.kind("https://twitch.tv/xqc"))
        assertEquals(SourceKind.YOUTUBE, Sources.kind("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(SourceKind.YOUTUBE, Sources.kind("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(SourceKind.INVALID, Sources.kind(""))
    }

    @Test
    fun extractsUrlFromSharedText() {
        assertEquals("https://youtu.be/abc", Sources.extractUrl("Смотри: https://youtu.be/abc."))
    }

    @Test
    fun hlsMediaPlaylist() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:2
            #EXT-X-MEDIA-SEQUENCE:1000
            #EXT-X-PROGRAM-DATE-TIME:2024-01-01T00:00:00.000Z
            #EXTINF:2.000,live
            seg1000.ts
            #EXTINF:2.002,Amazon|123
            https://cdn/ad.ts
            #EXT-X-TWITCH-PREFETCH:https://cdn/next.ts
            #EXTINF:1.500,live
            seg1002.ts
        """.trimIndent()
        val p = HlsParser.parse(text, "https://cdn.example/path/index.m3u8")
        assertFalse(p.isMaster)
        assertEquals(3, p.segments.size)
        assertEquals(1000L, p.segments[0].sequence)
        assertEquals("https://cdn.example/path/seg1000.ts", p.segments[0].uri)
        assertTrue(p.segments[1].isTwitchAd)
        assertFalse(p.segments[0].isTwitchAd)
        assertEquals(1002L, p.segments[2].sequence)
        assertEquals(1.5, p.segments[2].durationSec)
        assertFalse(p.endList)
    }

    @Test
    fun hlsFmp4InitSegment() {
        val p = HlsParser.parse("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:2.0,live\na.m4s\n#EXT-X-ENDLIST", "https://x/y/p.m3u8")
        assertEquals("https://x/y/init.mp4", p.initUri)
        assertTrue(p.endList)
    }

    @Test
    fun hlsMasterPlaylistPicksUpTo720() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080
            1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720
            720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=852x480
            480.m3u8
        """.trimIndent()
        val p = HlsParser.parse(text, "https://a/b/master.m3u8")
        assertTrue(p.isMaster)
        assertEquals("https://a/b/720.m3u8", HlsParser.pickVariant(p.variants, 720)!!.uri)
        assertEquals("https://a/b/480.m3u8", HlsParser.pickVariant(p.variants, 360)!!.uri)
    }

    @Test
    fun quietHoursOverMidnight() {
        val start = 23 * 60
        val end = 8 * 60
        assertTrue(QuietHours.isQuiet(23 * 60 + 30, start, end))
        assertTrue(QuietHours.isQuiet(3 * 60, start, end))
        assertFalse(QuietHours.isQuiet(8 * 60, start, end))
        assertFalse(QuietHours.isQuiet(12 * 60, start, end))
        val now = LocalDateTime.of(2024, 5, 1, 23, 30)
        assertEquals(LocalDateTime.of(2024, 5, 2, 8, 0), QuietHours.nextEnd(now, end))
    }

    @Test
    fun durationFormat() {
        assertEquals("1:02:03", TimeFormat.duration(3723.0))
        assertEquals("1:15", TimeFormat.duration(75.0))
    }
}
