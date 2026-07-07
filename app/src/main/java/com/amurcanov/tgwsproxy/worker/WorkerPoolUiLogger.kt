package com.amurcanov.tgwsproxy.worker

import android.util.Log

object WorkerPoolUiLogger {
    private const val TAG = "TgWsProxy"

    fun screenOpened() {
        Log.i(TAG, "Worker pool screen opened")
    }

    fun workerSelected(workerId: String) {
        Log.i(TAG, "Worker selected from UI: id=$workerId")
    }

    fun workerEditOpened(workerId: String) {
        Log.i(TAG, "Worker edit opened: id=$workerId")
    }

    fun workerSaved(workerId: String) {
        Log.i(TAG, "Worker saved from UI: id=$workerId")
    }

    fun workerDeleted(workerId: String) {
        Log.i(TAG, "Worker deleted from UI: id=$workerId")
    }

    fun workerEnabled(workerId: String) {
        Log.i(TAG, "Worker enabled from UI: id=$workerId")
    }

    fun workerDisabled(workerId: String) {
        Log.i(TAG, "Worker disabled from UI: id=$workerId")
    }

    fun strategyChanged(oldStrategy: WorkerSelectionStrategy, newStrategy: WorkerSelectionStrategy) {
        Log.i(TAG, "Worker strategy changed from UI: old=${oldStrategy.name}, new=${newStrategy.name}")
    }

    fun poolEnabledChanged(enabled: Boolean) {
        Log.i(TAG, "Worker pool enabled changed from UI: enabled=$enabled")
    }
}
