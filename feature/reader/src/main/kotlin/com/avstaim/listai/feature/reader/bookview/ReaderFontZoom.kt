package com.avstaim.listai.feature.reader.bookview

import kotlin.math.exp
import kotlin.math.ln

private const val MIN_READER_FONT_SIZE_SP = 12f
private const val MAX_READER_FONT_SIZE_SP = 32f
private const val DEFAULT_READER_FONT_SIZE_SP = 18f
private const val PINCH_JITTER_FACTOR = 1.06f
private const val MIN_PINCH_FACTOR = .25f
private const val MAX_PINCH_FACTOR = 4f

/** Applies one finished gesture to the same range accepted by ReaderPreferences. */
internal fun readerFontSizeAfterZoom(currentSizeSp: Float, factor: Float): Float {
    val current = currentSizeSp.takeIf(Float::isFinite)
        ?.coerceIn(MIN_READER_FONT_SIZE_SP, MAX_READER_FONT_SIZE_SP) ?: DEFAULT_READER_FONT_SIZE_SP
    if (!factor.isFinite() || factor <= 0f) return current
    return (current.toDouble() * factor).coerceIn(
        MIN_READER_FONT_SIZE_SP.toDouble(), MAX_READER_FONT_SIZE_SP.toDouble()).toFloat()
}

internal data class ReaderPinchUpdate(
    val ownsGesture: Boolean = false,
    val cancelPageTurn: Boolean = false,
    val finished: Boolean = false,
    val completedFactor: Float? = null,
)

/**
 * A second finger owns the entire contact sequence, including the remaining single finger.
 * The only publication occurs after every finger is released. No page or preference state
 * lives here; callers use ownership to suppress tap/hold/curl and commit the final factor.
 */
internal class ReaderPinchSession {
    var claimed: Boolean = false
        private set
    private var finished = false
    private var cancelled = false
    // Log accumulation preserves a return from any finite excursion without overflow.
    // Clamping intermediate moves would lose that excursion and change the final ratio.
    private var logFactor = 0.0

    fun update(pressedPointers: Int, incrementalFactor: Float = 1f,
        cancelled: Boolean = false): ReaderPinchUpdate {
        if (finished) return ReaderPinchUpdate()
        this.cancelled = this.cancelled || cancelled
        val claim = !claimed && pressedPointers >= 2
        if (claim) claimed = true
        if (!claimed) return ReaderPinchUpdate()
        if (pressedPointers >= 2 && incrementalFactor.isFinite() && incrementalFactor > 0f) {
            val next = logFactor + ln(incrementalFactor.toDouble())
            if (next.isFinite()) logFactor = next
        }
        if (pressedPointers <= 0) {
            finished = true
            val threshold = ln(PINCH_JITTER_FACTOR.toDouble())
            val meaningful = logFactor >= threshold || logFactor <= -threshold
            val factor = exp(logFactor.coerceIn(ln(MIN_PINCH_FACTOR.toDouble()),
                ln(MAX_PINCH_FACTOR.toDouble()))).toFloat()
            return ReaderPinchUpdate(ownsGesture = true, cancelPageTurn = claim, finished = true,
                completedFactor = factor.takeIf { meaningful && !this.cancelled })
        }
        return ReaderPinchUpdate(ownsGesture = true, cancelPageTurn = claim)
    }

    fun cancel() { cancelled = true }
}
