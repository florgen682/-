package app.clipradar

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Composition
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clipradar.core.CropPlanner
import app.clipradar.core.NRect
import app.clipradar.core.SubtitleCue
import app.clipradar.core.TimeRange
import app.clipradar.data.ClipLayout
import app.clipradar.media.ClipRenderer
import app.clipradar.media.FrameAnalyzer
import app.clipradar.media.FramePlan
import app.clipradar.media.RenderPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Рендер настоящим Media3 Transformer на эмуляторе и проверка кадров готового клипа.
 * Тестовое видео 1280×720: оранжевый фон, белый прямоугольник слева сверху («вебка»),
 * зелёный справа снизу и синий квадрат, который двигается.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class RenderTest {
    private val ctx = TestFiles.ctx
    private val inAspect = 1280.0 / 720
    private val cam = NRect(40.0 / 1280, 40.0 / 720, 260.0 / 1280, 200.0 / 720)
    private val fill = CropPlanner.fillCropSize(inAspect, 9.0 / 16)

    private fun render(name: String, plan: RenderPlan): File = runBlocking {
        val out = File(TestFiles.outDir, "$name.mp4")
        ClipRenderer.render(ctx, TestFiles.asset("sample.mp4"), out, plan)
        assertTrue("клип $name не создан", out.exists() && out.length() > 10_000)
        out
    }

    private fun basePlan(layout: ClipLayout, frame: FramePlan = FramePlan(fill.first, fill.second, emptyList())) = RenderPlan(
        parts = listOf(TimeRange(0.5, 4.5)),
        layout = layout,
        inAspect = inAspect,
        frame = frame,
    )

    private fun checkNoBars(name: String, clip: File) {
        for (t in listOf(0.5, 2.0, 3.5)) {
            val f = TestFiles.frameAt(clip, t)
            TestFiles.save(f, "${name}_${t}s.png")
            val black = TestFiles.blackRowFraction(f)
            TestFiles.report("render.txt", "$name t=$t кадр ${f.width}x${f.height}: чёрных строк ${"%.1f".format(black * 100)}%")
            assertTrue("$name: чёрные поля (${black * 100}% строк)", black < 0.01)
            assertTrue("$name: кадр не вертикальный", f.height > f.width)
        }
    }

    @Test
    fun fillHasNoBlackBars() {
        val samples = runBlocking { FrameAnalyzer.analyze(TestFiles.asset("sample.mp4"), 0.0, 5.0, detectFaces = false) }
        val track = CropPlanner.smartTrack(samples.samples, fill.first, followFaces = false)
        TestFiles.report("render.txt", "fill: центр обрезки следует за движением: ${track.map { "%.2f".format(it.cx) }}")
        checkNoBars("fill", render("fill", basePlan(ClipLayout.FILL, FramePlan(fill.first, fill.second, track))))
    }

    @Test
    fun blurHasNoBlackBars() {
        checkNoBars("blur", render("blur", basePlan(ClipLayout.BLUR)))
    }

    @Test
    fun streamLayoutPutsWebcamOnTopAndGameBelow() {
        val layout = CropPlanner.streamLayout(inAspect, cam, null)
        val clip = render(
            "stream",
            basePlan(ClipLayout.STREAM, FramePlan(fill.first, fill.second, emptyList(), layout.cam, layout.game, emptyList(), layout.camFraction)),
        )
        checkNoBars("stream", clip)
        val f = TestFiles.frameAt(clip, 2.0)
        val top = TestFiles.meanColor(f, 0.2, 0.08, 0.8, 0.32) // внутри верхней части (вебка = белый)
        val bottom = TestFiles.meanColor(f, 0.1, 0.55, 0.9, 0.95) // нижняя часть (игра = оранжевый фон)
        TestFiles.report("render.txt", "stream: верх $top, низ $bottom")
        assertTrue("вверху должна быть вебка (белая): $top", top.first > 200 && top.second > 200 && top.third > 200)
        assertTrue("внизу должна быть игра (оранжевая): $bottom", bottom.first > 180 && bottom.third < 120)
    }

    @Test
    fun subtitlesAreInLowerThird() {
        val plan = basePlan(ClipLayout.FILL).copy(cues = listOf(SubtitleCue(0.0, 10.0, "HELLO WORLD")))
        val clip = render("subtitles", plan)
        val f = TestFiles.frameAt(clip, 2.0)
        TestFiles.save(f, "subtitles.png")
        fun countTextPixels(y0: Double, y1: Double): Int {
            var n = 0
            for (y in (y0 * f.height).toInt() until (y1 * f.height).toInt() step 2) for (x in 0 until f.width step 2) {
                val p = f.getPixel(x, y)
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                if ((r > 235 && g > 235 && b > 235) || (r < 30 && g < 30 && b < 30)) n++
            }
            return n
        }
        val band = countTextPixels(0.62, 0.78)
        val topBand = countTextPixels(0.10, 0.30)
        TestFiles.report("render.txt", "subtitles: пикселей текста в нижней трети $band, вверху $topBand")
        assertTrue("субтитры должны быть в нижней трети", band > 2000 && topBand < band / 10)
    }

    /** Контрольный тест: старый режим «чёрные поля» проверка действительно ловит. */
    @Test
    fun letterboxIsDetectedAsBlackBars() = runBlocking {
        val out = File(TestFiles.outDir, "letterbox.mp4")
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(TestFiles.asset("sample.mp4"))))
            .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT))))
            .build()
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                Transformer.Builder(ctx).addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) = cont.resume(Unit)
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) =
                        cont.resumeWithException(exportException)
                }).build().start(item, out.absolutePath)
            }
        }
        val black = TestFiles.blackRowFraction(TestFiles.frameAt(out, 2.0))
        TestFiles.report("render.txt", "letterbox (старый режим): чёрных строк ${"%.1f".format(black * 100)}%")
        assertTrue("проверка должна находить чёрные поля", black > 0.3)
    }
}
