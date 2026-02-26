package com.example.myapplication.engine

import com.example.myapplication.config.AppConfig.Coordinates as Coords
import com.example.myapplication.utils.Logger

/**
 * Coordinate Mapper
 * Maps coordinates from compressed image space to actual device screen coordinates
 */
object CoordinateMapper {

    private const val TAG = "CoordinateMapper"

    // Target compression dimensions (from AppConfig)
    val COMPRESSED_WIDTH: Int get() = Coords.COMPRESSED_WIDTH
    val COMPRESSED_HEIGHT: Int get() = Coords.COMPRESSED_HEIGHT

    private val logger = Logger(TAG)

    /**
     * Map coordinates from compressed image space to real screen coordinates
     *
     * @param x X coordinate in compressed image space
     * @param y Y coordinate in compressed image space
     * @param realWidth Actual screen width
     * @param realHeight Actual screen height
     * @return Pair of mapped (x, y) coordinates in real screen space
     */
    fun mapToReal(
        x: Float,
        y: Float,
        realWidth: Int,
        realHeight: Int
    ): Pair<Float, Float> {
        val realX = x * realWidth / Coords.COMPRESSED_WIDTH
        val realY = y * realHeight / Coords.COMPRESSED_HEIGHT

        logger.d("Mapped ($x, $y) from ${Coords.COMPRESSED_WIDTH}x${Coords.COMPRESSED_HEIGHT} to ($realX, $realY) on ${realWidth}x${realHeight}")

        return realX to realY
    }

    /**
     * Map coordinates from real screen space to compressed image space
     *
     * @param x X coordinate in real screen space
     * @param y Y coordinate in real screen space
     * @param realWidth Actual screen width
     * @param realHeight Actual screen height
     * @return Pair of mapped (x, y) coordinates in compressed image space
     */
    fun mapToCompressed(
        x: Float,
        y: Float,
        realWidth: Int,
        realHeight: Int
    ): Pair<Float, Float> {
        val compressedX = x * Coords.COMPRESSED_WIDTH / realWidth
        val compressedY = y * Coords.COMPRESSED_HEIGHT / realHeight

        logger.d("Mapped ($x, $y) from ${realWidth}x${realHeight} to ($compressedX, $compressedY) on ${Coords.COMPRESSED_WIDTH}x${Coords.COMPRESSED_HEIGHT}")

        return compressedX to compressedY
    }

    /**
     * Map a list of coordinates from compressed to real space
     *
     * @param coordinates List of (x, y) pairs in compressed space
     * @param realWidth Actual screen width
     * @param realHeight Actual screen height
     * @return List of mapped (x, y) pairs in real space
     */
    fun mapListToReal(
        coordinates: List<Pair<Float, Float>>,
        realWidth: Int,
        realHeight: Int
    ): List<Pair<Float, Float>> {
        return coordinates.map { (x, y) ->
            mapToReal(x, y, realWidth, realHeight)
        }
    }

    /**
     * Map a rectangle from compressed to real space
     *
     * @param left Left coordinate in compressed space
     * @param top Top coordinate in compressed space
     * @param right Right coordinate in compressed space
     * @param bottom Bottom coordinate in compressed space
     * @param realWidth Actual screen width
     * @param realHeight Actual screen height
     * @return Mapped RectF in real space
     */
    fun mapRectToReal(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        realWidth: Int,
        realHeight: Int
    ): androidx.compose.ui.geometry.Rect {
        val (realLeft, realTop) = mapToReal(left, top, realWidth, realHeight)
        val (realRight, realBottom) = mapToReal(right, bottom, realWidth, realHeight)

        return androidx.compose.ui.geometry.Rect(
            left = realLeft,
            top = realTop,
            right = realRight,
            bottom = realBottom
        )
    }

    /**
     * Get scale factors for mapping
     *
     * @param realWidth Actual screen width
     * @param realHeight Actual screen height
     * @return Pair of (scaleX, scaleY) factors
     */
    fun getScaleFactors(realWidth: Int, realHeight: Int): Pair<Float, Float> {
        val scaleX = realWidth.toFloat() / Coords.COMPRESSED_WIDTH
        val scaleY = realHeight.toFloat() / Coords.COMPRESSED_HEIGHT

        logger.d("Scale factors: x=$scaleX, y=$scaleY")

        return scaleX to scaleY
    }

    /**
     * Validate if coordinates are within compressed image bounds
     *
     * @param x X coordinate in compressed space
     * @param y Y coordinate in compressed space
     * @return true if coordinates are valid
     */
    fun isValidCompressedCoordinate(x: Float, y: Float): Boolean {
        return x in 0f..Coords.COMPRESSED_WIDTH.toFloat() && y in 0f..Coords.COMPRESSED_HEIGHT.toFloat()
    }

    /**
     * Clamp coordinates to compressed image bounds
     *
     * @param x X coordinate in compressed space
     * @param y Y coordinate in compressed space
     * @return Clamped (x, y) pair
     */
    fun clampToCompressedBounds(x: Float, y: Float): Pair<Float, Float> {
        val clampedX = x.coerceIn(0f, Coords.COMPRESSED_WIDTH.toFloat())
        val clampedY = y.coerceIn(0f, Coords.COMPRESSED_HEIGHT.toFloat())

        if (clampedX != x || clampedY != y) {
            logger.w("Clamped coordinates ($x, $y) to ($clampedX, $clampedY)")
        }

        return clampedX to clampedY
    }

    /**
     * Calculate the center point of a rectangle in compressed space
     */
    fun getCenterInCompressedSpace(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): Pair<Float, Float> {
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        return centerX to centerY
    }

    /**
     * Convert percentage-based coordinates to compressed space
     */
    fun percentageToCompressed(
        xPercent: Float, // 0.0 to 1.0
        yPercent: Float  // 0.0 to 1.0
    ): Pair<Float, Float> {
        val x = xPercent * Coords.COMPRESSED_WIDTH
        val y = yPercent * Coords.COMPRESSED_HEIGHT
        return x to y
    }

    /**
     * Convert percentage-based coordinates to real screen space
     */
    fun percentageToReal(
        xPercent: Float,
        yPercent: Float,
        realWidth: Int,
        realHeight: Int
    ): Pair<Float, Float> {
        val x = xPercent * realWidth
        val y = yPercent * realHeight
        return x to y
    }
}
