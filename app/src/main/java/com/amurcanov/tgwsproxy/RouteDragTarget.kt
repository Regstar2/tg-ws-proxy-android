package com.amurcanov.tgwsproxy

import kotlin.math.roundToInt

/** Resolve the drop slot from the entire gesture, not from each incremental pointer event. */
internal object RouteDragTarget {
    fun index(
        startIndex: Int,
        count: Int,
        displacementPx: Float,
        rowStridePx: Float,
    ): Int {
        if (count <= 0 || startIndex !in 0 until count || rowStridePx <= 0f) {
            return startIndex
        }
        val steps = (displacementPx / rowStridePx)
            .coerceIn(-count.toFloat(), count.toFloat())
            .roundToInt()
        return (startIndex + steps).coerceIn(0, count - 1)
    }

    /** Use actual visible slots; disabled rows remain anchored during a drag. */
    fun indexForDisplaySlots(
        startActiveIndex: Int,
        displayOrder: List<RouteKind>,
        activeRoutes: List<RouteKind>,
        displacementPx: Float,
        rowStridePx: Float,
    ): Int {
        val slots = displayOrder.indices.filter { displayOrder[it] in activeRoutes }
        if (rowStridePx <= 0f || slots.size != activeRoutes.size ||
            startActiveIndex !in slots.indices
        ) return startActiveIndex

        val startSlot = slots[startActiveIndex].toFloat()
        val targetSlot = startSlot + displacementPx / rowStridePx
        var target = startActiveIndex
        if (targetSlot > startSlot) {
            for (index in startActiveIndex + 1 until slots.size) {
                val midpoint = (slots[index - 1] + slots[index]) / 2f
                if (targetSlot >= midpoint) target = index else break
            }
        } else {
            for (index in startActiveIndex - 1 downTo 0) {
                val midpoint = (slots[index] + slots[index + 1]) / 2f
                if (targetSlot <= midpoint) target = index else break
            }
        }
        return target
    }
}
