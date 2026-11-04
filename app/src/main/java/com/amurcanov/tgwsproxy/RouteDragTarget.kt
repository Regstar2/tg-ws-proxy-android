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
}
