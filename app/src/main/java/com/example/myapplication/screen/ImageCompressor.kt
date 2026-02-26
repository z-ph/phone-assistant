package com.example.myapplication.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.myapplication.config.AppConfig.Image as ImgConfig
import com.example.myapplication.utils.Logger
import kotlin.math.max
import kotlin.math.min

/**
 * Image Compressor
 * Compresses bitmap images to specified dimensions for efficient transmission
 */
class ImageCompressor {

    companion object {
        private const val TAG = "ImageCompressor"

        // Target dimensions for compression (from AppConfig)
        val TARGET_WIDTH: Int get() = ImgConfig.TARGET_WIDTH
        val TARGET_HEIGHT: Int get() = ImgConfig.TARGET_HEIGHT

        // Maximum file size (from AppConfig)
        val MAX_FILE_SIZE: Int get() = ImgConfig.MAX_FILE_SIZE
    }

    private val logger = Logger(TAG)

    /**
     * Compress a bitmap to target dimensions
     *
     * @param bitmap Original bitmap to compress
     * @param targetWidth Target width (default: 768)
     * @param targetHeight Target height (default: 1366)
     * @return Compressed bitmap
     */
    fun compress(
        bitmap: Bitmap,
        targetWidth: Int = TARGET_WIDTH,
        targetHeight: Int = TARGET_HEIGHT
    ): Bitmap {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        logger.d("Compressing bitmap from ${originalWidth}x${originalHeight} to ${targetWidth}x${targetHeight}")

        // Calculate aspect ratio
        val aspectRatio = originalWidth.toFloat() / originalHeight.toFloat()
        val targetAspectRatio = targetWidth.toFloat() / targetHeight.toFloat()

        val finalWidth: Int
        val finalHeight: Int

        // Decide how to scale based on aspect ratio
        if (aspectRatio > targetAspectRatio) {
            // Original is wider - fit to width
            finalWidth = targetWidth
            finalHeight = (targetWidth / aspectRatio).toInt()
        } else {
            // Original is taller - fit to height
            finalHeight = targetHeight
            finalWidth = (targetHeight * aspectRatio).toInt()
        }

        // Ensure minimum dimensions
        val scaledWidth = max(finalWidth, 100)
        val scaledHeight = max(finalHeight, 100)

        logger.d("Scaled dimensions: ${scaledWidth}x${scaledHeight}")

        return try {
            Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        } catch (e: Exception) {
            logger.e("Error compressing bitmap: ${e.message}")
            // Return original if compression fails
            bitmap
        }
    }

    /**
     * Compress bitmap with quality adjustment
     *
     * @param bitmap Original bitmap
     * @param quality JPEG quality (1-100)
     * @param targetWidth Target width
     * @param targetHeight Target height
     * @return Compressed bitmap
     */
    fun compressWithQuality(
        bitmap: Bitmap,
        quality: Int = 85,
        targetWidth: Int = TARGET_WIDTH,
        targetHeight: Int = TARGET_HEIGHT
    ): Bitmap {
        logger.d("Compressing with quality: $quality")

        // First resize
        val resized = compress(bitmap, targetWidth, targetHeight)

        // Note: Quality is applied during encoding (in Base64Encoder)
        return resized
    }

    /**
     * Compress bitmap to fit within max file size
     *
     * @param bitmap Original bitmap
     * @param maxSizeBytes Maximum file size in bytes
     * @param initialQuality Initial JPEG quality
     * @return Compressed bitmap
     */
    fun compressToMaxSize(
        bitmap: Bitmap,
        maxSizeBytes: Int = ImgConfig.MAX_FILE_SIZE,
        initialQuality: Int = 90
    ): Bitmap {
        logger.d("Compressing to max size: ${maxSizeBytes} bytes")

        var currentBitmap = bitmap
        var quality = initialQuality

        // First resize
        currentBitmap = compress(currentBitmap)

        // Then reduce quality if needed
        var size = estimateSize(currentBitmap, quality)
        var iteration = 0
        val maxIterations = 10

        while (size > maxSizeBytes && iteration < maxIterations && quality > 10) {
            quality -= 10
            size = estimateSize(currentBitmap, quality)
            iteration++
            logger.d("Iteration $iteration: quality=$quality, estimated size=$size")
        }

        logger.d("Final quality: $quality, estimated size: $size")
        return currentBitmap
    }

    /**
     * Compress with smart scaling based on original size
     */
    fun compressSmart(bitmap: Bitmap): Bitmap {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        logger.d("Smart compress: original size ${originalWidth}x${originalHeight}")

        return when {
            originalWidth > ImgConfig.TARGET_WIDTH * 2 || originalHeight > ImgConfig.TARGET_HEIGHT * 2 -> {
                // Large screen - use two-step scaling for better quality
                logger.d("Large screen detected, using two-step scaling")
                val intermediateWidth = (originalWidth * 0.5f).toInt()
                val intermediateHeight = (originalHeight * 0.5f).toInt()
                val intermediate = Bitmap.createScaledBitmap(bitmap, intermediateWidth, intermediateHeight, true)
                val result = compress(intermediate)
                if (intermediate != bitmap) {
                    intermediate.recycle()
                }
                result
            }
            originalWidth > ImgConfig.TARGET_WIDTH || originalHeight > ImgConfig.TARGET_HEIGHT -> {
                // Medium screen - standard compression
                compress(bitmap)
            }
            else -> {
                // Small screen - might need upscaling if too small
                if (originalWidth < ImgConfig.TARGET_WIDTH / 2 || originalHeight < ImgConfig.TARGET_HEIGHT / 2) {
                    val scaleUp = max(
                        ImgConfig.TARGET_WIDTH.toFloat() / originalWidth,
                        ImgConfig.TARGET_HEIGHT.toFloat() / originalHeight
                    )
                    if (scaleUp > 2f) {
                        val scaledWidth = (originalWidth * scaleUp).toInt()
                        val scaledHeight = (originalHeight * scaleUp).toInt()
                        logger.d("Small screen, upscaling to ${scaledWidth}x${scaledHeight}")
                        Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
                    } else {
                        bitmap
                    }
                } else {
                    bitmap
                }
            }
        }
    }

    /**
     * Estimate compressed size
     */
    private fun estimateSize(bitmap: Bitmap, quality: Int): Int {
        // Rough estimation based on dimensions and quality
        val pixels = bitmap.width * bitmap.height
        return (pixels * quality * 0.3).toInt()
    }

    /**
     * Decode and compress byte array
     */
    fun decodeAndCompress(
        bytes: ByteArray,
        targetWidth: Int = TARGET_WIDTH,
        targetHeight: Int = TARGET_HEIGHT
    ): Bitmap? {
        return try {
            logger.d("Decoding and compressing ${bytes.size} bytes")

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

            // Calculate sample size
            val sampleSize = calculateSampleSize(
                options.outWidth,
                options.outHeight,
                targetWidth,
                targetHeight
            )

            options.inSampleSize = sampleSize
            options.inJustDecodeBounds = false

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            compress(bitmap ?: return null, targetWidth, targetHeight)
        } catch (e: Exception) {
            logger.e("Error decoding and compressing: ${e.message}")
            null
        }
    }

    /**
     * Calculate sample size for efficient decoding
     */
    private fun calculateSampleSize(
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Int {
        var inSampleSize = 1

        if (width > targetWidth || height > targetHeight) {
            val halfWidth = width / 2
            val halfHeight = height / 2

            while (halfWidth / inSampleSize >= targetWidth && halfHeight / inSampleSize >= targetHeight) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }
}
