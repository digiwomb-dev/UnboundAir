package dev.digiwomb.unboundair.processing

import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.image.JpegInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for [PageProcessor] (SV-07): the page processing chain as a whole.
 *
 * The four acceptance criteria of SV-07 are pinned, one test each:
 *
 * 1. a dummy step can be plugged into the chain: the steps run in the given
 *    order, and a step that changes nothing passes the very same [PageImage]
 *    instance through, so the chain stays safe to extend without touching
 *    any existing step;
 * 2. the real chain (crop, then grayscale) runs end to end on a scanned
 *    page and returns a new [PageImage];
 * 3. the working directory is kept clean: the intermediate files written
 *    during the run are deleted, the final result file survives, and files
 *    that existed before the run are left alone; the input file is never
 *    touched;
 * 4. warnings of the steps are forwarded to the caller's [process.warn].
 *
 * The chain tests of criteria 2 to 4 run the real [CropStep] and
 * [GrayscaleStep], which invoke `jpegtran` (a system dependency of the
 * container, so the tests stay offline per DC-03) and therefore sit in the
 * integration layer of docs/teststrategie.md; the dummy step test of
 * criterion 1 is a pure JVM unit test. Every test copies its fixture into
 * [tempDir] and passes the processor a fresh [workDir], mirroring how the
 * chain receives pages in production; the committed fixtures are never
 * modified.
 */
class PageProcessorTest {
    @TempDir
    lateinit var tempDir: Path

    @TempDir
    lateinit var workDir: Path

    /**
     * SV-07 -- criterion 1. Two recording dummy steps are plugged into the
     * chain: they run in the order they were configured (a, then b), and
     * because neither changes the page, the very same [PageImage] instance
     * that entered the chain comes out, no file is written, and no warning
     * is emitted. This proves that a new step (rotation, deskew, or a no-op)
     * can be added by inserting it into the step list, without touching any
     * existing step.
     */
    @Test
    fun `SV-07 two dummy steps run in the given order and the page passes through unchanged`() {
        val src = TestImages.copy("envelope_dl_300dpi_raw.jpg", tempDir)
        val image = PageImage(src, JpegInfo.read(src))
        val calls = mutableListOf<String>()
        val processor = PageProcessor(listOf(RecordingStep("a", calls), RecordingStep("b", calls)))
        val warnings = mutableListOf<String>()
        val result = processor.process(image, workDir) { warnings += it }

        assertThat(calls)
            .`as`("the steps must run in the order they were configured")
            .isEqualTo(listOf("a", "b"))
        assertThat(result)
            .`as`("a step that changes nothing must pass the same instance through")
            .isSameAs(image)
        assertThat(Files.list(workDir).use { it.count() })
            .`as`("a no-op chain must not leave any file in the working directory")
            .isEqualTo(0L)
        assertThat(warnings).isEmpty()
    }

    /**
     * SV-07 -- criterion 2. The real chain (the default [CropStep] followed
     * by the default [GrayscaleStep]) runs the DL envelope end to end: the
     * crop writes `cropped.jpg`, the grayscale converts it to a single luma
     * component and writes `gray.jpg`, so the chain ends with a new
     * [PageImage] whose [JpegInfo] carries exactly one component, and the
     * result file exists in the working directory.
     */
    @Test
    fun `SV-07 the real chain crops and grayscales the page and returns a new single-luma page`() {
        val src = TestImages.copy("envelope_dl_300dpi_raw.jpg", tempDir)
        val image = PageImage(src, JpegInfo.read(src))
        assertThat(image.info.components)
            .`as`("the envelope fixture is a color page, so a single luma below proves the conversion")
            .isEqualTo(3)

        val result = PageProcessor(listOf(CropStep(), GrayscaleStep())).process(image, workDir)

        assertThat(result)
            .`as`("the crop writes a new file, so the chain must end with a new page instance")
            .isNotSameAs(image)
        assertThat(result.file)
            .`as`("the last step of the chain is the grayscale step, which writes gray.jpg")
            .isEqualTo(workDir.resolve("gray.jpg"))
        assertThat(Files.exists(result.file))
            .`as`("the result file must exist in the working directory")
            .isTrue()
        assertThat(result.info.components)
            .`as`("the grayscale step converts the three color components to a single luma")
            .isEqualTo(1)
    }

    /**
     * SV-07 -- criterion 3. The processor keeps the working directory clean:
     * after the crop-to-grayscale run, the intermediate `cropped.jpg` is
     * deleted while the final result `gray.jpg` survives. A file that
     * already existed in the working directory before the run is left
     * alone, and the input file (in a separate [tempDir]) stays
     * byte-identical, because it is owned by the caller.
     */
    @Test
    fun `SV-07 the run deletes intermediate files, keeps pre-existing files, and never touches the input`() {
        val marker = workDir.resolve("marker.txt")
        val markerContent = "marker".encodeToByteArray()
        Files.write(marker, markerContent)
        val src = TestImages.copy("envelope_dl_300dpi_raw.jpg", tempDir)
        val inputBytes = Files.readAllBytes(src)
        val image = PageImage(src, JpegInfo.read(src))

        PageProcessor(listOf(CropStep(), GrayscaleStep())).process(image, workDir)

        assertThat(Files.exists(workDir.resolve("cropped.jpg")))
            .`as`("the intermediate crop output must be deleted after the run")
            .isFalse()
        assertThat(Files.exists(workDir.resolve("gray.jpg")))
            .`as`("the final result file must survive the cleanup")
            .isTrue()
        assertThat(Files.readAllBytes(marker))
            .`as`("a file that existed before the run must be left alone")
            .isEqualTo(markerContent)
        assertThat(Files.readAllBytes(src))
            .`as`("the input file is owned by the caller and must stay untouched")
            .isEqualTo(inputBytes)
        val remaining =
            Files.list(workDir).use { listing ->
                listing.map { file -> file.fileName.toString() }.sorted().toList()
            }
        assertThat(remaining)
            .`as`("after the run the working directory holds exactly the marker and the result")
            .isEqualTo(listOf("gray.jpg", "marker.txt"))
    }

    /**
     * SV-07 -- criterion 4. The dark page fixture carries no detectable
     * paper, so the crop step warns and carries the page through uncropped
     * (the very same [PageImage] instance). The processor must forward that
     * warning to the caller's warn lambda: the chain's warning channel is
     * the caller's, and steps and cleanup report through one and the same
     * channel.
     */
    @Test
    fun `SV-07 a warning of a step is forwarded to the caller`() {
        val src = TestImages.copy("dark_page.jpg", tempDir)
        val image = PageImage(src, JpegInfo.read(src))
        val warnings = mutableListOf<String>()

        val result = PageProcessor(listOf(CropStep())).process(image, workDir) { warnings += it }

        assertThat(result)
            .`as`("no paper is detected in the dark page, so it is carried through uncropped")
            .isSameAs(image)
        assertThat(warnings)
            .`as`("the crop step must report the missing paper through the caller's warn lambda")
            .isNotEmpty()
        assertThat(warnings).anySatisfy { assertThat(it).contains("no paper") }
    }
}

/**
 * A no-op [ProcessingStep] for [PageProcessorTest]: it records its [name] in
 * the shared [calls] list and returns the very same [PageImage] instance it
 * received, exactly like a step that changes nothing. It is file-private, so
 * it cannot leak into the production chain.
 */
private class RecordingStep(
    private val stepName: String,
    private val calls: MutableList<String>,
) : ProcessingStep {
    override val name: String
        get() = stepName

    override fun apply(
        image: PageImage,
        workDir: Path,
        warn: (String) -> Unit,
    ): PageImage {
        calls += name
        return image
    }
}
