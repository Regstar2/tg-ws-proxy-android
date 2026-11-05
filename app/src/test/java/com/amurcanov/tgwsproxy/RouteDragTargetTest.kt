package com.amurcanov.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteDragTargetTest {
    @Test fun oneGestureCanCrossSeveralRows() {
        assertEquals(3, RouteDragTarget.index(0, 5, 3.1f * 85f, 85f))
        assertEquals(0, RouteDragTarget.index(3, 5, -3.2f * 85f, 85f))
    }

    @Test fun stopsAtFirstAndLastEnabledRoute() {
        assertEquals(4, RouteDragTarget.index(1, 5, 900f, 85f))
        assertEquals(0, RouteDragTarget.index(3, 5, -900f, 85f))
        assertEquals(0, RouteDragTarget.index(0, 1, 900f, 85f))
    }

    @Test fun shortMoveDoesNotReorderAndInvalidSizeIsSafe() {
        assertEquals(2, RouteDragTarget.index(2, 5, 30f, 85f))
        assertEquals(2, RouteDragTarget.index(2, 5, -30f, 85f))
        assertEquals(2, RouteDragTarget.index(2, 5, 300f, 0f))
    }
    @Test fun dragCrossesInactiveSlotsWithoutMovingThem() {
        val order = listOf(RouteKind.CF_PROXY_WS, RouteKind.AWG_WARP,
            RouteKind.WORKER_WS, RouteKind.DIRECT_WS, RouteKind.TCP_FALLBACK)
        val active = listOf(RouteKind.CF_PROXY_WS, RouteKind.WORKER_WS, RouteKind.TCP_FALLBACK)
        assertEquals(0, RouteDragTarget.indexForDisplaySlots(0, order, active, 30f, 85f))
        assertEquals(1, RouteDragTarget.indexForDisplaySlots(0, order, active, 170f, 85f))
        assertEquals(2, RouteDragTarget.indexForDisplaySlots(0, order, active, 360f, 85f))
        assertEquals(0, RouteDragTarget.indexForDisplaySlots(2, order, active, -360f, 85f))
        assertEquals(2, RouteDragTarget.indexForDisplaySlots(2, order, active, 340f, 85f))
    }

}
