package com.yanhuozhang.offline.system

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

internal object WechatWindowResolver {
    fun findRoot(
        activeRoot: AccessibilityNodeInfo?,
        windows: List<AccessibilityWindowInfo>,
        eventWindowId: Int,
    ): AccessibilityNodeInfo? {
        if (activeRoot?.packageName?.toString() == WECHAT_PACKAGE &&
            activeRoot.windowId == eventWindowId
        ) {
            return activeRoot
        }

        var bestRoot: AccessibilityNodeInfo? = null
        var bestIsApplication = false
        var bestLayer = Int.MIN_VALUE
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() != WECHAT_PACKAGE) {
                continue
            }
            if (window.id == eventWindowId) {
                return root
            }

            val isApplication = window.type == AccessibilityWindowInfo.TYPE_APPLICATION
            val betterWindow = bestRoot == null ||
                (isApplication && !bestIsApplication) ||
                (isApplication == bestIsApplication && window.layer > bestLayer)
            if (betterWindow) {
                bestRoot = root
                bestIsApplication = isApplication
                bestLayer = window.layer
            }
        }

        if (activeRoot?.packageName?.toString() == WECHAT_PACKAGE) {
            return activeRoot
        }
        return bestRoot ?: activeRoot?.takeIf { root -> root.packageName == null }
    }

    private const val WECHAT_PACKAGE = "com.tencent.mm"
}
