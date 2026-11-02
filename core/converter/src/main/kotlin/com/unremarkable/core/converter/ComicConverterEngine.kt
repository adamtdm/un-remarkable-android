package com.unremarkable.core.converter

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

data class ConversionProgress(
    val current: Int,
    val total: Int,
    val message: String
)

class ComicConverterEngine {

    fun convertCbz(
        cbzFile: File,
        config: ConverterConfig = ConverterConfig(),
        onProgress: (ConversionProgress) -> Unit = {}
    ): ByteArray {
        val zip = ZipFile(cbzFile)
        val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

        val entries = zip.entries().asSequence()
            .filter { entry ->
                !entry.isDirectory &&
                        imageExtensions.contains(entry.name.substringAfterLast('.', "").lowercase()) &&
                        !entry.name.contains("__MACOSX")
            }
            .sortedWith(NaturalOrderComparator)
            .toList()

        val quality = QualityProfiles.getProfile(config.qualityStop)
        val targetWidth = quality.maxWidth.coerceAtMost(config.targetProfile.width)
        val targetHeight = quality.maxHeight.coerceAtMost(config.targetProfile.height)

        val processedPages = mutableListOf<PdfPageImage>()
        val totalEntries = entries.size

        for ((index, entry) in entries.withIndex()) {
            onProgress(ConversionProgress(index + 1, totalEntries, "Optimizing page ${index + 1} of $totalEntries"))

            zip.getInputStream(entry).use { stream ->
                val originalImage = ImageIO.read(stream) ?: return@use

                val subPages = if (config.splitSpreads && isLandscapeSpread(originalImage)) {
                    val halfW = originalImage.width / 2
                    val leftHalf = originalImage.getSubimage(0, 0, halfW, originalImage.height)
                    val rightHalf = originalImage.getSubimage(halfW, 0, originalImage.width - halfW, originalImage.height)

                    if (config.mangaOrder) {
                        listOf(rightHalf, leftHalf)
                    } else {
                        listOf(leftHalf, rightHalf)
                    }
                } else {
                    listOf(originalImage)
                }

                for (subImg in subPages) {
                    val scaled = scaleImageToFit(subImg, targetWidth, targetHeight)
                    val jpegBytes = encodeJpeg(scaled, quality.jpegQuality)
                    processedPages.add(PdfPageImage(jpegBytes, scaled.width, scaled.height))
                }
            }
        }

        zip.close()
        return PdfImageWriter.toByteArray(processedPages)
    }

    private fun isLandscapeSpread(image: BufferedImage): Boolean {
        return image.width > (image.height * 1.15f)
    }

    fun scaleImageToFit(source: BufferedImage, maxW: Int, maxH: Int): BufferedImage {
        val srcW = source.width
        val srcH = source.height

        val scale = minOf(maxW.toFloat() / srcW, maxH.toFloat() / srcH, 1.0f)
        val dstW = (srcW * scale).toInt().coerceAtLeast(1)
        val dstH = (srcH * scale).toInt().coerceAtLeast(1)

        if (dstW == srcW && dstH == srcH && source.type == BufferedImage.TYPE_INT_RGB) {
            return source
        }

        val scaled = BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_RGB)
        val g = scaled.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.drawImage(source, 0, 0, dstW, dstH, null)
        g.dispose()

        return scaled
    }

    fun encodeJpeg(image: BufferedImage, quality: Float): ByteArray {
        val rgbImage = if (image.type != BufferedImage.TYPE_INT_RGB) {
            val converted = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
            val g = converted.createGraphics()
            g.drawImage(image, 0, 0, null)
            g.dispose()
            converted
        } else {
            image
        }

        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam
        param.compressionMode = ImageWriteParam.MODE_EXPLICIT
        param.compressionQuality = quality.coerceIn(0.1f, 1.0f)

        val baos = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(baos).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(rgbImage, null, null), param)
            writer.dispose()
        }

        return baos.toByteArray()
    }

    private object NaturalOrderComparator : Comparator<java.util.zip.ZipEntry> {
        override fun compare(a: java.util.zip.ZipEntry, b: java.util.zip.ZipEntry): Int {
            return a.name.compareTo(b.name, ignoreCase = true)
        }
    }
}
