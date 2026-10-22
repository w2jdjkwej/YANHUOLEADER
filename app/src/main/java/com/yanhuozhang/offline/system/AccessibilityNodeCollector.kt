package com.yanhuozhang.offline.system

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.yanhuozhang.offline.core.ScreenBounds
import com.yanhuozhang.offline.core.ScreenTextNode

internal object AccessibilityNodeCollector {
    private data class QueueEntry(
        val node: AccessibilityNodeInfo,
        val clickableContainer: ScreenBounds?,
    )

    /** [nodes] 为采集到的文本节点；[truncated] 表示遍历因为访问上限提前结束，深层节点可能没被读到。 */
    data class CollectedNodes(
        val nodes: List<ScreenTextNode>,
        val truncated: Boolean,
    )

    fun collect(
        root: AccessibilityNodeInfo,
        maximumNodes: Int,
    ): List<ScreenTextNode> = collectDetailed(root, maximumNodes).nodes

    fun collectDetailed(
        root: AccessibilityNodeInfo,
        maximumNodes: Int,
    ): CollectedNodes {
        return collectDetailed(root, maximumNodes, visibleOnly = false)
    }

    fun collectDetailed(
        root: AccessibilityNodeInfo,
        maximumNodes: Int,
        visibleOnly: Boolean,
    ): CollectedNodes {
        if (maximumNodes <= 0) {
            return CollectedNodes(emptyList(), truncated = true)
        }

        val queue = ArrayDeque<QueueEntry>()
        val result = mutableListOf<ScreenTextNode>()
        queue.addLast(QueueEntry(root, null))
        var visited = 0

        while (queue.isNotEmpty() && visited < maximumNodes) {
            val entry = queue.removeFirst()
            val node = entry.node
            visited += 1

            val bounds = node.screenBounds()
            val clickableContainer = if (node.isClickable) bounds else entry.clickableContainer
            val visibleValues = listOf(node.text, node.contentDescription)
                .mapNotNull { value -> value?.toString()?.trim() }
                .filter { value -> value.isNotEmpty() }
                .distinct()
            for (value in visibleValues) {
                result += ScreenTextNode(
                    text = value,
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom,
                    clickableContainer = clickableContainer,
                )
            }

            for (childIndex in 0 until node.childCount) {
                val child = node.getChild(childIndex)
                if (child != null && (!visibleOnly || child.isVisibleToUser)) {
                    queue.addLast(QueueEntry(child, clickableContainer))
                }
            }
        }
        return CollectedNodes(result, truncated = queue.isNotEmpty())
    }

    private fun AccessibilityNodeInfo.screenBounds(): ScreenBounds {
        val rect = Rect()
        getBoundsInScreen(rect)
        return ScreenBounds(
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
        )
    }
}
