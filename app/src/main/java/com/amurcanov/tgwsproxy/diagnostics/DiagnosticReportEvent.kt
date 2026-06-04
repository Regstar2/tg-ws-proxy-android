package com.amurcanov.tgwsproxy.diagnostics

import android.content.Intent

sealed interface DiagnosticReportEvent {
    data object CopySuccess : DiagnosticReportEvent
    data class ShareReady(val intent: Intent) : DiagnosticReportEvent
    data class Failed(val messageRes: Int) : DiagnosticReportEvent
}
