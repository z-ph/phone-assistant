package com.example.myapplication.api

import com.example.myapplication.utils.Logger
import kotlinx.coroutines.delay

/**
 * Circuit Breaker Pattern Implementation
 * Prevents cascading failures by failing fast when a service is unavailable
 *
 * States:
 * - CLOSED: Normal operation, requests pass through
 * - OPEN: Circuit is tripped, requests fail immediately
 * - HALF_OPEN: Testing if service has recovered
 */
class CircuitBreaker(
    private val failureThreshold: Int = 5,
    private val successThreshold: Int = 2,
    private val resetTimeoutMs: Long = 60000,
    private val tag: String = "CircuitBreaker"
) {

    private val logger = Logger(tag)

    enum class State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    @Volatile
    private var failureCount = 0

    @Volatile
    private var successCount = 0

    @Volatile
    private var lastFailureTime = 0L

    @Volatile
    private var state = State.CLOSED

    /**
     * Get current circuit state
     */
    fun getState(): State = state

    /**
     * Get failure count
     */
    fun getFailureCount(): Int = failureCount

    /**
     * Execute a block with circuit breaker protection
     *
     * @param block The suspend function to execute
     * @return Result of the block, or null if circuit is open
     */
    suspend fun <T> execute(block: suspend () -> T): T? {
        if (!canExecute()) {
            logger.w("Circuit is OPEN - failing fast")
            return null
        }

        return try {
            val result = block()
            onSuccess()
            result
        } catch (e: Exception) {
            onFailure(e)
            null
        }
    }

    /**
     * Execute with timeout and circuit breaker
     *
     * @param timeoutMs Timeout in milliseconds
     * @param block The suspend function to execute
     * @return Result of the block, or null if circuit is open or timeout
     */
    suspend fun <T> executeWithTimeout(timeoutMs: Long, block: suspend () -> T): T? {
        if (!canExecute()) {
            logger.w("Circuit is OPEN - failing fast")
            return null
        }

        return try {
            val result = kotlinx.coroutines.withTimeout(timeoutMs) { block() }
            onSuccess()
            result
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.e("Timeout after ${timeoutMs}ms")
            onFailure(e)
            null
        } catch (e: Exception) {
            logger.e("Execution error: ${e.message}")
            onFailure(e)
            null
        }
    }

    /**
     * Check if request can be executed
     */
    private fun canExecute(): Boolean {
        return when (state) {
            State.CLOSED -> true
            State.OPEN -> {
                val timeSinceLastFailure = System.currentTimeMillis() - lastFailureTime
                if (timeSinceLastFailure >= resetTimeoutMs) {
                    logger.i("Transitioning from OPEN to HALF_OPEN")
                    state = State.HALF_OPEN
                    successCount = 0
                    true
                } else {
                    false
                }
            }
            State.HALF_OPEN -> true
        }
    }

    /**
     * Record successful execution
     */
    private fun onSuccess() {
        failureCount = 0

        when (state) {
            State.HALF_OPEN -> {
                successCount++
                if (successCount >= successThreshold) {
                    logger.i("Circuit recovered - transitioning to CLOSED")
                    state = State.CLOSED
                    successCount = 0
                }
            }
            State.CLOSED -> {
                // Stay closed
            }
            State.OPEN -> {
                // Should not happen
                logger.w("Unexpected success in OPEN state")
            }
        }
    }

    /**
     * Record failed execution
     */
    private fun onFailure(e: Exception) {
        failureCount++
        lastFailureTime = System.currentTimeMillis()

        when (state) {
            State.CLOSED -> {
                if (failureCount >= failureThreshold) {
                    logger.e("Circuit tripped - transitioning to OPEN (failures: $failureCount)")
                    state = State.OPEN
                } else {
                    logger.w("Failure recorded ($failureCount/$failureThreshold)")
                }
            }
            State.HALF_OPEN -> {
                logger.e("Failure in HALF_OPEN - transitioning back to OPEN")
                state = State.OPEN
            }
            State.OPEN -> {
                // Already open, just update failure time
            }
        }
    }

    /**
     * Manually reset the circuit breaker
     */
    fun reset() {
        logger.i("Circuit breaker manually reset")
        state = State.CLOSED
        failureCount = 0
        successCount = 0
        lastFailureTime = 0
    }

    /**
     * Force circuit to OPEN state (for testing or manual intervention)
     */
    fun forceOpen() {
        logger.w("Circuit breaker forced OPEN")
        state = State.OPEN
        lastFailureTime = System.currentTimeMillis()
    }

    /**
     * Get circuit breaker statistics
     */
    fun getStats(): CircuitBreakerStats {
        return CircuitBreakerStats(
            state = state,
            failureCount = failureCount,
            successCount = successCount,
            failureThreshold = failureThreshold,
            resetTimeoutMs = resetTimeoutMs,
            timeSinceLastFailure = System.currentTimeMillis() - lastFailureTime
        )
    }
}

/**
 * Circuit breaker statistics
 */
data class CircuitBreakerStats(
    val state: CircuitBreaker.State,
    val failureCount: Int,
    val successCount: Int,
    val failureThreshold: Int,
    val resetTimeoutMs: Long,
    val timeSinceLastFailure: Long
)

/**
 * Circuit breaker factory for managing multiple circuit breakers
 */
object CircuitBreakerFactory {

    private val circuitBreakers = mutableMapOf<String, CircuitBreaker>()
    private val logger = Logger("CircuitBreakerFactory")

    /**
     * Get or create a circuit breaker by name
     */
    fun getOrCreate(
        name: String,
        failureThreshold: Int = 5,
        successThreshold: Int = 2,
        resetTimeoutMs: Long = 60000
    ): CircuitBreaker {
        return circuitBreakers.getOrPut(name) {
            logger.d("Creating circuit breaker: $name")
            CircuitBreaker(
                failureThreshold = failureThreshold,
                successThreshold = successThreshold,
                resetTimeoutMs = resetTimeoutMs,
                tag = "CircuitBreaker:$name"
            )
        }
    }

    /**
     * Get a circuit breaker by name (returns null if not exists)
     */
    fun get(name: String): CircuitBreaker? = circuitBreakers[name]

    /**
     * Reset all circuit breakers
     */
    fun resetAll() {
        circuitBreakers.values.forEach { it.reset() }
        logger.i("All circuit breakers reset")
    }

    /**
     * Get stats for all circuit breakers
     */
    fun getAllStats(): Map<String, CircuitBreakerStats> {
        return circuitBreakers.mapValues { it.value.getStats() }
    }
}
