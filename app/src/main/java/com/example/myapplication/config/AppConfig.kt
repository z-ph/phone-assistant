package com.example.myapplication.config

/**
 * Centralized application configuration
 *
 * All hardcoded values should be moved here for easy maintenance and testing.
 */
object AppConfig {

    /**
     * Coordinate system configuration
     */
    object Coordinates {
        /** AI uses this normalized width for coordinate calculations */
        const val NORMALIZED_WIDTH = 1080

        /** AI uses this normalized height for coordinate calculations */
        const val NORMALIZED_HEIGHT = 2400

        /** Compressed image width for API transmission */
        const val COMPRESSED_WIDTH = 768

        /** Compressed image height for API transmission */
        const val COMPRESSED_HEIGHT = 1366
    }

    /**
     * Agent engine configuration
     */
    object Agent {
        /** Maximum execution steps before stopping */
        const val DEFAULT_MAX_STEPS = 20

        /** Delay between steps in milliseconds */
        const val STEP_DELAY_MS = 1000L
    }

    /**
     * Timeout configuration (in milliseconds or seconds as indicated)
     */
    object Timeouts {
        /** HTTP connection timeout in seconds */
        const val CONNECT_TIMEOUT_SECONDS = 30L

        /** HTTP read timeout in seconds */
        const val READ_TIMEOUT_SECONDS = 60L

        /** HTTP write timeout in seconds */
        const val WRITE_TIMEOUT_SECONDS = 60L

        /** Screen capture timeout in milliseconds */
        const val SCREEN_CAPTURE_TIMEOUT_MS = 5000L

        /** API request timeout in milliseconds */
        const val API_REQUEST_TIMEOUT_MS = 30000L

        /** Overall task timeout in milliseconds */
        const val TASK_TIMEOUT_MS = 60000L
    }

    /**
     * Retry configuration
     */
    object Retry {
        /** Maximum retry attempts for screen capture */
        const val MAX_RETRIES = 3

        /** Default retry attempts for actions */
        const val DEFAULT_RETRY_ATTEMPTS = 2

        /** Delay between retries in milliseconds */
        const val RETRY_DELAY_MS = 1000L
    }

    /**
     * Image processing configuration
     */
    object Image {
        /** Target width for image compression */
        const val TARGET_WIDTH = 768

        /** Target height for image compression */
        const val TARGET_HEIGHT = 1366

        /** Maximum file size in bytes (500KB) */
        const val MAX_FILE_SIZE = 500 * 1024

        /** Default JPEG compression quality (1-100) */
        const val DEFAULT_JPEG_QUALITY = 85

        /** Maximum image bytes before encoding (1MB) */
        const val MAX_IMAGE_BYTES = 1024 * 1024
    }

    /**
     * Context management configuration
     */
    object Context {
        /** Maximum messages to keep in conversation context */
        const val MAX_MESSAGES = 30

        /** Maximum image messages (images consume more tokens) */
        const val MAX_IMAGE_MESSAGES = 5

        /** Threshold to trigger context summarization */
        const val SUMMARY_THRESHOLD = 20
    }

    /**
     * Action delay configuration
     */
    object ActionDelays {
        /** Default gesture duration in milliseconds */
        const val DEFAULT_GESTURE_DURATION_MS = 300L

        /** Short delay between actions */
        const val SHORT_DELAY_MS = 200L

        /** Medium delay between actions */
        const val MEDIUM_DELAY_MS = 400L

        /** Long delay between actions */
        const val LONG_DELAY_MS = 500L

        /** Queue process interval in milliseconds */
        const val QUEUE_PROCESS_INTERVAL_MS = 100L

        /** Action timeout in milliseconds */
        const val ACTION_TIMEOUT_MS = 5000L
    }
}
