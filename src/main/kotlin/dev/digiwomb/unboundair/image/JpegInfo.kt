package dev.digiwomb.unboundair.image

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.nio.file.Path
import javax.imageio.IIOException
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.ImageInputStream

/**
 * The JPEG structure of one scanned page (SV-01, OF-06): the decoded
 * dimensions, the number of stored color components, and the iMCU size
 * derived from the component sampling factors of the image itself.
 *
 * Everything is read from the file through the JDK's built-in JPEG reader
 * and its standard metadata tree, so no property of the image is assumed
 * (OF-06). The iMCU size is what the lossless crop (SV-01) must align to:
 * the 4:2:2 material produced by the real scanner yields 16x8 pixels, and
 * the 4:2:0 synthetic test images yield 16x16.
 *
 * @property width the decoded image width in pixels.
 * @property height the decoded image height in pixels.
 * @property components the number of color components the JPEG stores
 *   (3 for YCbCr/RGB, 1 for grayscale).
 * @property imcuWidth the iMCU width in pixels: 8 times the horizontal
 *   sampling factor of the first (luma) component.
 * @property imcuHeight the iMCU height in pixels: 8 times the vertical
 *   sampling factor of the first (luma) component.
 */
data class JpegInfo(
    val width: Int,
    val height: Int,
    val components: Int,
    val imcuWidth: Int,
    val imcuHeight: Int,
) {
    companion object {
        // The standard metadata format of the JDK's built-in JPEG reader.
        private const val METADATA_FORMAT_NAME = "javax_imageio_jpeg_image_1.0"

        // Element names of the standard JPEG metadata tree.
        private const val MARKER_SEQUENCE_ELEMENT = "markerSequence"
        private const val SOF_ELEMENT = "sof"
        private const val COMPONENT_SPEC_ELEMENT = "componentSpec"

        // Attribute names of a `componentSpec` element.
        private const val HSAMPLING_FACTOR = "HsamplingFactor"
        private const val VSAMPLING_FACTOR = "VsamplingFactor"

        // One sampling factor unit covers 8 pixels of the luma grid.
        private const val PIXELS_PER_SAMPLING_UNIT = 8

        /**
         * Reads the JPEG structure of the file at [path] (OF-06).
         *
         * The dimensions come from [ImageReader.getWidth] and
         * [ImageReader.getHeight]; the component count and the iMCU size
         * come from the `sof` element of the `markerSequence` in the
         * standard metadata tree `javax_imageio_jpeg_image_1.0`. Each
         * `componentSpec` child of the `sof` element is one stored color
         * component, and the sampling factors of the first one (the luma
         * component) scaled by 8 give the iMCU size.
         *
         * @param path the JPEG file to read.
         * @return the [JpegInfo] of the image stored in [path].
         * @throws IllegalArgumentException the file is missing, is not a
         *   readable JPEG, or its metadata lacks the expected elements; the
         *   message names [path].
         */
        fun read(path: Path): JpegInfo {
            val input = openImageStream(path)
            try {
                return readFrom(input, path)
            } finally {
                runCatching { input.close() }
            }
        }

        /**
         * Reads the [JpegInfo] from the open [input] stream, disposing the
         * reader when done.
         *
         * @throws IllegalArgumentException no image reader supports the
         *   stream, the image cannot be decoded, or its metadata lacks the
         *   expected elements; the message names [path].
         */
        private fun readFrom(
            input: ImageInputStream,
            path: Path,
        ): JpegInfo {
            val reader =
                ImageIO.getImageReaders(input).asSequence().firstOrNull()
                    ?: throw IllegalArgumentException(
                        "Cannot read $path as a JPEG: no image reader supports this file",
                    )
            try {
                reader.setInput(input, true, true)
                val (width, height) = readDimensions(reader, path)
                val tree = metadataTree(reader, path)
                val specs = componentSpecs(tree, path)
                val luma = specs.first()
                return JpegInfo(
                    width = width,
                    height = height,
                    components = specs.size,
                    imcuWidth = PIXELS_PER_SAMPLING_UNIT * samplingFactor(luma, HSAMPLING_FACTOR, path),
                    imcuHeight = PIXELS_PER_SAMPLING_UNIT * samplingFactor(luma, VSAMPLING_FACTOR, path),
                )
            } finally {
                reader.dispose()
            }
        }

        /**
         * Opens [path] as an ImageIO image stream.
         *
         * @throws IllegalArgumentException the file is missing or no image
         *   SPI can handle it; the message names [path].
         */
        private fun openImageStream(path: Path): ImageInputStream =
            ImageIO.createImageInputStream(path.toFile())
                ?: throw IllegalArgumentException(
                    "Cannot read $path as a JPEG: the file is missing or is not a readable image",
                )

        /**
         * Reads the decoded image dimensions from the [reader].
         *
         * @throws IllegalArgumentException the dimensions cannot be
         *   determined; the message names [path].
         */
        private fun readDimensions(
            reader: ImageReader,
            path: Path,
        ): Pair<Int, Int> =
            try {
                reader.getWidth(0) to reader.getHeight(0)
            } catch (e: IIOException) {
                throw IllegalArgumentException(
                    "Cannot read the dimensions of $path: ${e.message}",
                    e,
                )
            }

        /**
         * Returns the standard JPEG metadata tree of the [reader].
         *
         * @throws IllegalArgumentException the image carries no (or a
         *   different) metadata format, or the tree cannot be built; the
         *   message names [path].
         */
        private fun metadataTree(
            reader: ImageReader,
            path: Path,
        ): Node {
            try {
                val metadata =
                    reader.getImageMetadata(0)
                        ?: throw IllegalArgumentException(
                            "Cannot read $path as a JPEG: the image has no metadata",
                        )
                if (METADATA_FORMAT_NAME !in metadata.getMetadataFormatNames()) {
                    throw IllegalArgumentException(
                        "Cannot read $path as a JPEG: expected metadata format " +
                            "'$METADATA_FORMAT_NAME' but found " +
                            "'${metadata.getMetadataFormatNames().contentToString()}'",
                    )
                }
                return metadata.getAsTree(METADATA_FORMAT_NAME)
            } catch (e: IIOException) {
                throw IllegalArgumentException(
                    "Cannot read the JPEG metadata of $path: ${e.message}",
                    e,
                )
            }
        }

        /**
         * Returns the `componentSpec` elements of the `sof` element in the
         * marker sequence of [tree], in document order.
         *
         * @throws IllegalArgumentException the `markerSequence`, the `sof`
         *   element, or the component specs are missing; the message names
         *   [path].
         */
        private fun componentSpecs(
            tree: Node,
            path: Path,
        ): List<Element> {
            val markerSequence = childElement(tree, MARKER_SEQUENCE_ELEMENT, path)
            val sof = childElement(markerSequence, SOF_ELEMENT, path)
            return childElements(sof, COMPONENT_SPEC_ELEMENT, path)
        }

        /**
         * Returns the child elements named [name] of [parent], in document
         * order.
         *
         * @throws IllegalArgumentException [parent] has no such children;
         *   the message names [path].
         */
        private fun childElements(
            parent: Node,
            name: String,
            path: Path,
        ): List<Element> {
            val children = mutableListOf<Element>()
            var node = parent.firstChild
            while (node != null) {
                if (node is Element && node.nodeName == name) {
                    children.add(node)
                }
                node = node.nextSibling
            }
            if (children.isEmpty()) {
                throw IllegalArgumentException(
                    "Cannot read $path as a JPEG: the metadata has no '$name' elements",
                )
            }
            return children
        }

        /**
         * Returns the single child element named [name] of [parent].
         *
         * @throws IllegalArgumentException [parent] has no such child; the
         *   message names [path].
         */
        private fun childElement(
            parent: Node,
            name: String,
            path: Path,
        ): Element = childElements(parent, name, path).first()

        /**
         * Reads the sampling-factor [attribute] of one component [spec].
         *
         * @throws IllegalArgumentException the attribute is missing or is
         *   not a number; the message names [path].
         */
        private fun samplingFactor(
            spec: Element,
            attribute: String,
            path: Path,
        ): Int {
            val raw = spec.getAttribute(attribute)
            return raw.toIntOrNull()
                ?: throw IllegalArgumentException(
                    "Cannot read $path as a JPEG: the metadata has no usable " +
                        "'$attribute' value (found '$raw')",
                )
        }
    }
}
