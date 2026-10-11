package com.example.voicebrainlive.api

import android.util.Log
import kotlinx.coroutines.delay
import kotlin.math.pow

/**
 * API Retry Helper - Handles API call retries with exponential backoff
 * 
 * Provides a robust way to retry failed API calls with configurable
 * retry count, delay, and backoff strategy.
 * 
 * Usage:
 * ```
 * val result = ApiRetryHelper.executeWithRetry(
 *     maxRetries = 3,
 *     initialDelayMs = 1000L,
 *     backoffMultiplier = 2.0
 * ) {
 *     // Your API call here
 *     geminiApi.generateContent(prompt)
 * }
 * ```
 */
object ApiRetryHelper {
    private const val TAG = "ApiRetryHelper"
    
    /**
     * Execute a suspend function with retry logic
     * 
     * @param maxRetries Maximum number of retry attempts (default: 3)
     * @param initialDelayMs Initial delay before first retry in milliseconds (default: 1000)
     * @param backoffMultiplier Multiplier for exponential backoff (default: 2.0)
     * @param retryableExceptions Set of exception classes that should trigger retry (default: all)
     * @param block The suspend function to execute
     * @return Result of the block function
     * @throws Exception If all retries fail, throws the last exception
     */
    suspend fun <T> executeWithRetry(
        maxRetries: Int = 3,
        initialDelayMs: Long = 1000L,
        backoffMultiplier: Double = 2.0,
        retryableExceptions: Set<Class<out Throwable>> = emptySet(),
        block: suspend () -> T
    ): T {
        var lastException: Exception? = null
        
        for (attempt in 0..maxRetries) {
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                
                // Check if this exception is retryable
                if (retryableExceptions.isNotEmpty() && !retryableExceptions.any { it.isInstance(e) }) {
                    Log.e(TAG, "Non-retryable exception: ${e.message}")
                    throw e
                }
                
                // Don't retry on last attempt
                if (attempt == maxRetries) {
                    Log.e(TAG, "All $maxRetries retries failed: ${e.message}")
                    break
                }
                
                // Calculate delay with exponential backoff
                val delayMs = (initialDelayMs * backoffMultiplier.pow(attempt)).toLong()
                Log.w(TAG, "Attempt ${attempt + 1} failed: ${e.message}. Retrying in ${delayMs}ms...")
                
                delay(delayMs)
            }
        }
        
        throw lastException ?: RuntimeException("Unknown error after $maxRetries retries")
    }
    
    /**
     * Execute a blocking function with retry logic (for non-suspend functions)
     */
    fun <T> executeWithRetryBlocking(
        maxRetries: Int = 3,
        initialDelayMs: Long = 1000L,
        backoffMultiplier: Double = 2.0,
        block: () -> T
    ): T {
        var lastException: Exception? = null
        
        for (attempt in 0..maxRetries) {
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                
                if (attempt == maxRetries) {
                    Log.e(TAG, "All $maxRetries retries failed: ${e.message}")
                    break
                }
                
                val delayMs = (initialDelayMs * backoffMultiplier.pow(attempt)).toLong()
                Log.w(TAG, "Attempt ${attempt + 1} failed: ${e.message}. Retrying in ${delayMs}ms...")
                
                Thread.sleep(delayMs)
            }
        }
        
        throw lastException ?: RuntimeException("Unknown error after $maxRetries retries")
    }
    
    /**
     * Check if an exception is a network-related error (retryable)
     */
    fun isNetworkError(e: Throwable): Boolean {
        val message = e.message?.lowercase() ?: ""
        return message.contains("timeout") ||
               message.contains("connection") ||
               message.contains("network") ||
               message.contains("socket") ||
               message.contains("unreachable") ||
               message.contains("dns") ||
               e is java.net.UnknownHostException ||
               e is java.net.SocketTimeoutException ||
               e is java.io.IOException
    }
    
    /**
     * Check if an exception is a server error (5xx - retryable)
     */
    fun isServerError(e: Throwable): Boolean {
        val message = e.message?.lowercase() ?: ""
        return message.contains("500") ||
               message.contains("502") ||
               message.contains("503") ||
               message.contains("504") ||
               message.contains("internal server error") ||
               message.contains("bad gateway") ||
               message.contains("service unavailable")
    }
    
    /**
     * Check if an exception is a client error (4xx - NOT retryable)
     */
    fun isClientError(e: Throwable): Boolean {
        val message = e.message?.lowercase() ?: ""
        return message.contains("400") ||
               message.contains("401") ||
               message.contains("403") ||
               message.contains("404") ||
               message.contains("429") ||
               message.contains("bad request") ||
               message.contains("unauthorized") ||
               message.contains("forbidden") ||
               message.contains("not found") ||
               message.contains("rate limit")
    }
    
    /**
     * Get user-friendly error message based on exception type
     */
    fun getUserFriendlyMessage(e: Throwable): String {
        return when {
            isClientError(e) -> {
                if (e.message?.contains("429") == true || e.message?.contains("rate limit") == true) {
                    "API rate limit reached. Please wait a moment and try again."
                } else if (e.message?.contains("401") == true || e.message?.contains("unauthorized") == true) {
                    "API key is invalid. Please check your settings."
                } else {
                    "Request error. Please check your input and try again."
                }
            }
            isServerError(e) -> {
                "Server is temporarily unavailable. Please try again later."
            }
            isNetworkError(e) -> {
                "Network connection error. Please check your internet connection."
            }
            else -> {
                "An error occurred: ${e.message ?: "Unknown error"}"
            }
        }
    }
}