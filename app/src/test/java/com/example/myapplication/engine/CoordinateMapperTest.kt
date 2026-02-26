package com.example.myapplication.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for CoordinateMapper
 *
 * Tests coordinate mapping between compressed image space and real screen coordinates.
 * These tests verify the core mathematical transformations without Android dependencies.
 *
 * Note: Tests use returnDefaultValues = true in gradle to handle android.util.Log calls
 */
class CoordinateMapperTest {

    // Use the same constants as CoordinateMapper for consistency
    private val compressedWidth = 768
    private val compressedHeight = 1366

    // ========== mapToReal Tests ==========

    @Test
    fun `mapToReal origin coordinates return origin`() {
        // Origin (0, 0) should map to origin regardless of target resolution
        val (realX, realY) = CoordinateMapper.mapToReal(0f, 0f, 1080, 2400)

        assertEquals(0f, realX, 0.001f)
        assertEquals(0f, realY, 0.001f)
    }

    @Test
    fun `mapToReal same resolution returns same coordinates`() {
        // When real dimensions match compressed dimensions, coordinates should be identical
        val (realX, realY) = CoordinateMapper.mapToReal(384f, 683f, compressedWidth, compressedHeight)

        assertEquals(384f, realX, 0.001f)
        assertEquals(683f, realY, 0.001f)
    }

    @Test
    fun `mapToReal double resolution returns double coordinates`() {
        // Double the target resolution should double the coordinates
        val (realX, realY) = CoordinateMapper.mapToReal(384f, 683f, compressedWidth * 2, compressedHeight * 2)

        assertEquals(768f, realX, 0.001f)
        assertEquals(1366f, realY, 0.001f)
    }

    @Test
    fun `mapToReal half resolution returns half coordinates`() {
        // Half the target resolution should halve the coordinates
        val (realX, realY) = CoordinateMapper.mapToReal(384f, 683f, compressedWidth / 2, compressedHeight / 2)

        assertEquals(192f, realX, 0.001f)
        assertEquals(341.5f, realY, 0.001f)
    }

    @Test
    fun `mapToReal common device resolution 1080x2400`() {
        // Test mapping to a common device resolution
        val (realX, realY) = CoordinateMapper.mapToReal(384f, 683f, 1080, 2400)

        // Expected: 384 * 1080 / 768 = 540, 683 * 2400 / 1366 ≈ 1200
        assertEquals(540f, realX, 0.001f)
        assertEquals(1200f, realY, 1f) // Allow 1 pixel tolerance
    }

    // ========== mapToCompressed Tests ==========

    @Test
    fun `mapToCompressed reverses mapToReal`() {
        val originalX = 384f
        val originalY = 683f
        val realWidth = 1080
        val realHeight = 2400

        // Map to real coordinates
        val (realX, realY) = CoordinateMapper.mapToReal(originalX, originalY, realWidth, realHeight)

        // Map back to compressed
        val (backX, backY) = CoordinateMapper.mapToCompressed(realX, realY, realWidth, realHeight)

        // Should get back the original values
        assertEquals(originalX, backX, 0.01f)
        assertEquals(originalY, backY, 0.01f)
    }

    @Test
    fun `mapToCompressed origin returns origin`() {
        val (compressedX, compressedY) = CoordinateMapper.mapToCompressed(0f, 0f, 1080, 2400)

        assertEquals(0f, compressedX, 0.001f)
        assertEquals(0f, compressedY, 0.001f)
    }

    // ========== getScaleFactors Tests ==========

    @Test
    fun `getScaleFactors returns correct ratios`() {
        val (scaleX, scaleY) = CoordinateMapper.getScaleFactors(1080, 2400)

        // Expected: 1080 / 768 = 1.40625, 2400 / 1366 ≈ 1.757
        assertEquals(1080f / compressedWidth, scaleX, 0.001f)
        assertEquals(2400f / compressedHeight, scaleY, 0.001f)
    }

    @Test
    fun `getScaleFactors same resolution returns one`() {
        val (scaleX, scaleY) = CoordinateMapper.getScaleFactors(compressedWidth, compressedHeight)

        assertEquals(1f, scaleX, 0.001f)
        assertEquals(1f, scaleY, 0.001f)
    }

    // ========== isValidCompressedCoordinate Tests ==========

    @Test
    fun `isValidCompressedCoordinate origin is valid`() {
        assertTrue(CoordinateMapper.isValidCompressedCoordinate(0f, 0f))
    }

    @Test
    fun `isValidCompressedCoordinate maxBounds are valid`() {
        assertTrue(CoordinateMapper.isValidCompressedCoordinate(compressedWidth.toFloat(), compressedHeight.toFloat()))
    }

    @Test
    fun `isValidCompressedCoordinate center is valid`() {
        val centerX = compressedWidth / 2f
        val centerY = compressedHeight / 2f
        assertTrue(CoordinateMapper.isValidCompressedCoordinate(centerX, centerY))
    }

    @Test
    fun `isValidCompressedCoordinate negativeX is invalid`() {
        assertFalse(CoordinateMapper.isValidCompressedCoordinate(-1f, 100f))
    }

    @Test
    fun `isValidCompressedCoordinate negativeY is invalid`() {
        assertFalse(CoordinateMapper.isValidCompressedCoordinate(100f, -1f))
    }

    @Test
    fun `isValidCompressedCoordinate beyondMaxX is invalid`() {
        assertFalse(CoordinateMapper.isValidCompressedCoordinate(compressedWidth + 1f, 100f))
    }

    @Test
    fun `isValidCompressedCoordinate beyondMaxY is invalid`() {
        assertFalse(CoordinateMapper.isValidCompressedCoordinate(100f, compressedHeight + 1f))
    }

    // ========== clampToCompressedBounds Tests ==========

    @Test
    fun `clampToCompressedBounds validCoordinates unchanged`() {
        val (clampedX, clampedY) = CoordinateMapper.clampToCompressedBounds(384f, 683f)

        assertEquals(384f, clampedX, 0.001f)
        assertEquals(683f, clampedY, 0.001f)
    }

    @Test
    fun `clampToCompressedBounds negativeX clamped to zero`() {
        val (clampedX, clampedY) = CoordinateMapper.clampToCompressedBounds(-100f, 100f)

        assertEquals(0f, clampedX, 0.001f)
        assertEquals(100f, clampedY, 0.001f)
    }

    @Test
    fun `clampToCompressedBounds negativeY clamped to zero`() {
        val (clampedX, clampedY) = CoordinateMapper.clampToCompressedBounds(100f, -100f)

        assertEquals(100f, clampedX, 0.001f)
        assertEquals(0f, clampedY, 0.001f)
    }

    @Test
    fun `clampToCompressedBounds beyondMax clamped to max`() {
        val (clampedX, clampedY) = CoordinateMapper.clampToCompressedBounds(
            compressedWidth + 100f,
            compressedHeight + 100f
        )

        assertEquals(compressedWidth.toFloat(), clampedX, 0.001f)
        assertEquals(compressedHeight.toFloat(), clampedY, 0.001f)
    }

    // ========== percentageToCompressed Tests ==========

    @Test
    fun `percentageToCompressed zeroPercent returns origin`() {
        val (x, y) = CoordinateMapper.percentageToCompressed(0f, 0f)

        assertEquals(0f, x, 0.001f)
        assertEquals(0f, y, 0.001f)
    }

    @Test
    fun `percentageToCompressed hundredPercent returns max`() {
        val (x, y) = CoordinateMapper.percentageToCompressed(1f, 1f)

        assertEquals(compressedWidth.toFloat(), x, 0.001f)
        assertEquals(compressedHeight.toFloat(), y, 0.001f)
    }

    @Test
    fun `percentageToCompressed fiftyPercent returns center`() {
        val (x, y) = CoordinateMapper.percentageToCompressed(0.5f, 0.5f)

        assertEquals(compressedWidth / 2f, x, 0.001f)
        assertEquals(compressedHeight / 2f, y, 0.001f)
    }

    // ========== getCenterInCompressedSpace Tests ==========

    @Test
    fun `getCenterInCompressedSpace returns centerOfRectangle`() {
        val (centerX, centerY) = CoordinateMapper.getCenterInCompressedSpace(0f, 0f, 100f, 100f)

        assertEquals(50f, centerX, 0.001f)
        assertEquals(50f, centerY, 0.001f)
    }

    @Test
    fun `getCenterInCompressedSpace asymmetricRectangle correctCenter`() {
        val (centerX, centerY) = CoordinateMapper.getCenterInCompressedSpace(0f, 0f, 200f, 100f)

        assertEquals(100f, centerX, 0.001f)
        assertEquals(50f, centerY, 0.001f)
    }
}
