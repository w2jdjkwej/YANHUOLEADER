package com.yanhuozhang.offline.settings

import com.yanhuozhang.offline.core.ScreenBounds

/**
 * 微信支付成功页截图识别商家的设置。只影响本机识别行为，不进备份。
 *
 * 裁剪区默认值按 1200×2608 真机样本实测：“支付成功”在 7.4%–10.5%，商家名 17.8%–20.9%，
 * 金额 22.1%–28.8%，优惠券从 34.3% 开始。裁剪区要同时包住商家名和金额，金额用于交叉校验。
 */
data class MerchantOcrSettings(
    val enabled: Boolean = true,
    val debugEnabled: Boolean = false,
    val cropTopPercent: Int = DEFAULT_CROP_TOP_PERCENT,
    val cropBottomPercent: Int = DEFAULT_CROP_BOTTOM_PERCENT,
) {
    /** 按屏幕尺寸换算裁剪区，左右保持全宽；异常配置回落到默认值。 */
    fun cropBounds(width: Int, height: Int): ScreenBounds {
        val validCrop = isValidCrop(cropTopPercent, cropBottomPercent)
        val topPercent = if (validCrop) cropTopPercent else DEFAULT_CROP_TOP_PERCENT
        val bottomPercent = if (validCrop) cropBottomPercent else DEFAULT_CROP_BOTTOM_PERCENT
        val top = (height * topPercent / 100).coerceIn(0, maxOf(height - 1, 0))
        val bottom = (height * bottomPercent / 100).coerceIn(top + 1, maxOf(height, top + 1))
        return ScreenBounds(left = 0, top = top, right = width, bottom = bottom)
    }

    companion object {
        const val DEFAULT_CROP_TOP_PERCENT = 10
        const val DEFAULT_CROP_BOTTOM_PERCENT = 40

        fun isValidCrop(topPercent: Int, bottomPercent: Int): Boolean {
            return topPercent in 0..99 && bottomPercent in 1..100 && topPercent < bottomPercent
        }
    }
}
