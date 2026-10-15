package com.amurcanov.tgwsproxy.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerScreenAPresentationTest {
    @Test fun emptyPool_allowsAddingButNotChecking() {
        val state = workerScreenAPresentation(true, false, 0, 0, false, "")
        assertTrue(state.showEmptyState)
        assertTrue(state.canChangeConfiguration)
        assertFalse(state.canCheckAll)
        assertFalse(state.showActualRuntime)
    }

    @Test fun mixedPool_showsWorkersAndAllowsChecks() {
        val state = workerScreenAPresentation(true, false, 3, 2, false, "")
        assertFalse(state.showEmptyState)
        assertFalse(state.showNoEnabledWarning)
        assertTrue(state.canCheckAll)
    }

    @Test fun disabledWorkers_stillShowListAndWarning() {
        val state = workerScreenAPresentation(true, false, 2, 0, false, "")
        assertFalse(state.showEmptyState)
        assertTrue(state.showNoEnabledWarning)
        assertFalse(state.canCheckAll)
    }

    @Test fun runningProxy_isReadOnlyAndShowsRuntime() {
        val state = workerScreenAPresentation(true, true, 2, 1, false, "cf_worker_ws")
        assertFalse(state.canChangeConfiguration)
        assertFalse(state.canCheckAll)
        assertTrue(state.showActualRuntime)
    }

    @Test fun stoppedProxy_neverClaimsActiveRuntime() {
        val state = workerScreenAPresentation(true, false, 1, 1, false, "cf_worker_ws")
        assertFalse(state.showActualRuntime)
    }

    @Test fun noPool_preservesLegacySingleWorkerMode() {
        val state = workerScreenAPresentation(false, false, 2, 2, false, "cf_worker_ws")
        assertFalse(state.showEmptyState)
        assertFalse(state.showNoEnabledWarning)
        assertFalse(state.canCheckAll)
        assertFalse(state.showActualRuntime)
    }

    @Test fun checkingAll_disablesCompetingCheck() {
        val state = workerScreenAPresentation(true, false, 1, 1, true, "cf_worker_ws")
        assertFalse(state.canCheckAll)
    }
    @Test fun fallbackToDirect_hidesStaleRuntimeWorker() {
        val state = workerScreenAPresentation(true, true, 2, 2, false, "direct_ws")
        assertFalse(state.showActualRuntime)
    }

    @Test fun unknownRoute_hidesStaleRuntimeWorker() {
        val state = workerScreenAPresentation(true, true, 1, 1, false, "")
        assertFalse(state.showActualRuntime)
    }
}
