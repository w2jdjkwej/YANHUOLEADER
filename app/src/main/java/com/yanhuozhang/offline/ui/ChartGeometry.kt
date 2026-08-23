package com.yanhuozhang.offline.ui

internal data class SquareBounds(
    val left: Float,
    val top: Float,
    val size: Float,
)

internal fun centeredSquareBounds(width: Float, height: Float): SquareBounds {
    val size = minOf(width, height).coerceAtLeast(0f)
    return SquareBounds(
        left = (width - size) / 2f,
        top = (height - size) / 2f,
        size = size,
    )
}

internal data class TrendBarGeometry(
    val baselineY: Float,
    val topY: Float,
    val shouldDraw: Boolean,
)

internal fun trendBarGeometry(
    canvasHeight: Float,
    barWidth: Float,
    bottomPadding: Float,
    amount: Long,
    maximumAmount: Long,
): TrendBarGeometry {
    val safeCanvasHeight = canvasHeight.coerceAtLeast(0f)
    val safeBarWidth = barWidth.coerceAtLeast(0f)
    val baselineY = (
        safeCanvasHeight - bottomPadding.coerceAtLeast(0f) - safeBarWidth / 2f
        ).coerceAtLeast(0f)
    val shouldDraw = amount > 0 && maximumAmount > 0 && baselineY > 0f && safeBarWidth > 0f
    if (!shouldDraw) {
        return TrendBarGeometry(
            baselineY = baselineY,
            topY = baselineY,
            shouldDraw = false,
        )
    }

    val highestCenterY = (safeBarWidth / 2f).coerceAtMost(baselineY)
    val usableHeight = baselineY - highestCenterY
    val ratio = (amount.toDouble() / maximumAmount.toDouble()).coerceIn(0.0, 1.0)
    return TrendBarGeometry(
        baselineY = baselineY,
        topY = baselineY - usableHeight * ratio.toFloat(),
        shouldDraw = true,
    )
}
