package com.example.myapplication.screen

import android.graphics.Bitmap
import android.util.Base64
import com.example.myapplication.config.AppConfig.Image as ImgConfig
import com.example.myapplication.utils.Logger
import java.io.ByteArrayOutputStream

/**
 * Base64 Encoder
 * Converts bitmap images to base64 encoded strings for API transmission
 */
class Base64Encoder {

    companion object {
        private const val TAG = "Base64Encoder"

        // Default compression settings (from AppConfig)
        val DEFAULT_JPEG_QUALITY: Int get() = ImgConfig.DEFAULT_JPEG_QUALITY
        private const val DEFAULT_PNG_QUALITY = 100

        // Maximum image size in bytes before encoding (from AppConfig)
        val MAX_IMAGE_BYTES: Int get() = ImgConfig.MAX_IMAGE_BYTES
    }

    private val logger = Logger(TAG)
    private val compressor = ImageCompressor()

    /**
     * Encode bitmap to base64 string
     *
     * @param bitmap Bitmap to encode
     * @param format Image format (JPEG or PNG)
     * @param quality Compression quality for JPEG (1-100)
     * @param compress Whether to compress before encoding
     * @return Base64 encoded string (without data URI prefix)
     */
    fun encode(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = DEFAULT_JPEG_QUALITY,
        compress: Boolean = true
    ): String? {
        return try {
            val bitmapToEncode = if (compress) {
                compressor.compress(bitmap)
            } else {
                bitmap
            }

            val stream = ByteArrayOutputStream()
            val compressed = bitmapToEncode.compress(format, quality, stream)

            if (!compressed) {
                logger.w("Failed to compress bitmap")
                return null
            }

            val bytes = stream.toByteArray()
            logger.d("Encoding ${bytes.size} bytes to base64")

            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            logger.d("Encoded to ${base64.length} characters")

            base64
        } catch (e: Exception) {
            logger.e("Error encoding bitmap to base64: ${e.message}")
            null
        } finally {
            if (compress && bitmap != compressor.compress(bitmap)) {
                // Clean up compressed bitmap if it's different
            }
        }
    }

    /**
     * Encode bitmap to base64 with data URI prefix
     *
     * @param bitmap Bitmap to encode
     * @param format Image format
     * @param quality Compression quality
     * @return Base64 encoded string with data URI prefix
     */
    fun encodeWithPrefix(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = DEFAULT_JPEG_QUALITY
    ): String? {
        val base64 = encode(bitmap, format, quality) ?: return null

        val mimeType = when (format) {
            Bitmap.CompressFormat.JPEG -> "image/jpeg"
            Bitmap.CompressFormat.PNG -> "image/png"
            else -> "image/jpeg"
        }

        return "data:$mimeType;base64,$base64"
    }

    /**
     * Encode with automatic quality adjustment to meet size limit
     *
     * @param bitmap Bitmap to encode
     * @param maxSizeBytes Maximum size in bytes
     * @return Base64 encoded string or null if failed
     */
    fun encodeWithSizeLimit(
        bitmap: Bitmap,
        maxSizeBytes: Int = MAX_IMAGE_BYTES
    ): String? {
        logger.d("Encoding with size limit: $maxSizeBytes bytes")

        var quality = DEFAULT_JPEG_QUALITY
        var base64: String? = null
        var attempts = 0
        val maxAttempts = 10

        while (attempts < maxAttempts) {
            val bitmapToUse = compressor.compress(bitmap)
            val stream = ByteArrayOutputStream()
            bitmapToUse.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            val bytes = stream.toByteArray()

            logger.d("Attempt $attempts: quality=$quality, size=${bytes.size} bytes")

            if (bytes.size <= maxSizeBytes) {
                base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                logger.d("Successfully encoded at quality $quality")
                break
            }

            quality = (quality * 0.8).toInt()
            if (quality < 10) {
                quality = 10
            }
            attempts++
        }

        if (base64 == null) {
            logger.w("Failed to encode within size limit")
        }

        return base64
    }

    /**
     * Encode raw bytes to base64
     *
     * @param bytes Raw image bytes
     * @return Base64 encoded string
     */
    fun encodeBytes(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Decode base64 string to bitmap
     *
     * @param base64 Base64 encoded string
     * @return Decoded bitmap or null if failed
     */
    fun decode(base64: String): Bitmap? {
        return try {
            logger.d("Decoding base64 string (${base64.length} chars)")

            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            logger.d("Decoded ${bytes.size} bytes")

            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            logger.e("Error decoding base64: ${e.message}")
            null
        }
    }

    /**
     * Decode base64 with data URI prefix
     *
     * @param base64WithPrefix Base64 string with data URI prefix
     * @return Decoded bitmap or null if failed
     */
    fun decodeWithPrefix(base64WithPrefix: String): Bitmap? {
        val base64 = if (base64WithPrefix.contains(",")) {
            base64WithPrefix.substringAfterLast(",")
        } else {
            base64WithPrefix
        }
        return decode(base64)
    }

    /**
     * Get estimated encoded size
     *
     * @param bitmap Bitmap to check
     * @param format Image format
     * @param quality Compression quality
     * @return Estimated size in bytes
     */
    fun estimateEncodedSize(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = DEFAULT_JPEG_QUALITY
    ): Int {
        val stream = ByteArrayOutputStream()
        bitmap.compress(format, quality, stream)
        return stream.size()
    }

    /**
     * Batch encode multiple bitmaps
     *
     * @param bitmaps List of bitmaps to encode
     * @param format Image format
     * @param quality Compression quality
     * @return List of base64 strings (null for failed encodings)
     */
    fun encodeBatch(
        bitmaps: List<Bitmap>,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = DEFAULT_JPEG_QUALITY
    ): List<String?> {
        return bitmaps.map { bitmap ->
            encode(bitmap, format, quality)
        }
    }
}
