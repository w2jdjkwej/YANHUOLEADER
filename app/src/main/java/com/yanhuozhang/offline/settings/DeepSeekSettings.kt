package com.yanhuozhang.offline.settings

enum class DeepSeekModel(
    val apiValue: String,
    val displayName: String,
) {
    FLASH(
        apiValue = "deepseek-v4-flash",
        displayName = "V4 Flash",
    ),
    PRO(
        apiValue = "deepseek-v4-pro",
        displayName = "V4 Pro",
    ),
    ;

    companion object {
        fun fromApiValue(value: String): DeepSeekModel {
            return entries.firstOrNull { model -> model.apiValue == value } ?: FLASH
        }

        fun isSupported(value: String): Boolean {
            return entries.any { model -> model.apiValue == value }
        }
    }
}

enum class DeepSeekThinkingMode(
    val apiValue: String,
    val displayName: String,
) {
    DISABLED(
        apiValue = "disabled",
        displayName = "非思考",
    ),
    ENABLED(
        apiValue = "enabled",
        displayName = "思考",
    ),
    ;

    companion object {
        fun fromApiValue(value: String): DeepSeekThinkingMode {
            return entries.firstOrNull { mode -> mode.apiValue == value } ?: DISABLED
        }

        fun isSupported(value: String): Boolean {
            return entries.any { mode -> mode.apiValue == value }
        }
    }
}
