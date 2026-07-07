package com.amurcanov.tgwsproxy



data class DestinationModeStatsEntry(

    val destinationMode: String,

    val originalParsedDst: String = "",

    val workerDst: String = "",

    val mappedDc: Int = 0,

    val isMedia: Boolean = false,

    val flowsealMediaFixApplied: Boolean = false,

    val sessionsTotal: Long = 0,

    val sessionsZeroDown: Long = 0,

    val sessionsBidirectional: Long = 0,

    val sessionsWithDownBytes: Long = 0,

    val zeroDownSessions: Long = 0,

    val upBytesTotal: Long = 0,

    val downBytesTotal: Long = 0,

    val avgDurationMs: Long = 0,

    val mediaFixApplied: Long = 0,

    val closeReason: String = "",

)



object DestinationModeStatsParser {

    fun parse(raw: String?): List<DestinationModeStatsEntry> {

        if (raw.isNullOrBlank()) {

            return emptyList()

        }

        return raw.split('|').mapNotNull { bucket ->

            val colonIdx = bucket.indexOf(':')

            if (colonIdx <= 0) {

                return@mapNotNull null

            }

            val mode = bucket.substring(0, colonIdx)

            val fields = bucket.substring(colonIdx + 1).split(':').associate { part ->

                val eq = part.indexOf('=')

                if (eq <= 0) {

                    part to ""

                } else {

                    part.substring(0, eq) to part.substring(eq + 1)

                }

            }

            val destinationMode = fields["destination_mode"]?.ifBlank { mode } ?: mode

            val sessionsBidirectional = fields.longValue("sessions_bidirectional")

                .takeIf { it > 0 }

                ?: fields.longValue("sessions_with_down_bytes")

            val sessionsZeroDown = fields.longValue("sessions_zero_down")

                .takeIf { it > 0 }

                ?: fields.longValue("zero_down_sessions")

            DestinationModeStatsEntry(

                destinationMode = destinationMode,

                originalParsedDst = fields["original_parsed_dst"].orEmpty(),

                workerDst = fields["worker_dst"].orEmpty(),

                mappedDc = fields["mapped_dc"]?.toIntOrNull() ?: 0,

                isMedia = fields["is_media"] == "1",

                flowsealMediaFixApplied = fields["flowseal_media_fix_applied"] == "1",

                sessionsTotal = fields.longValue("sessions_total"),

                sessionsZeroDown = sessionsZeroDown,

                sessionsBidirectional = sessionsBidirectional,

                sessionsWithDownBytes = sessionsBidirectional,

                zeroDownSessions = sessionsZeroDown,

                upBytesTotal = fields.longValue("up_bytes_total"),

                downBytesTotal = fields.longValue("down_bytes_total"),

                avgDurationMs = fields.longValue("avg_duration_ms"),

                mediaFixApplied = fields.longValue("media_fix_applied"),

                closeReason = fields["close_reason"].orEmpty(),

            )

        }

    }



    private fun Map<String, String>.longValue(key: String): Long =

        this[key]?.toLongOrNull() ?: 0L

}


