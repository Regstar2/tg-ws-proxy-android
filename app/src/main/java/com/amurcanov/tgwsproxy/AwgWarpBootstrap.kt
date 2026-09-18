package com.amurcanov.tgwsproxy

internal data class ExistingAwgBootstrapCandidate(
    val configPath: String,
    val source: AwgWarpProfileSource,
    val selected: Boolean,
)

internal fun orderedExistingAwgBootstrapProfiles(
    profiles: List<AwgWarpProfileSummary>,
): List<AwgWarpProfileSummary> {
    return profiles
        .asSequence()
        .filter { summary -> summary.metadata.health == AwgWarpProfileHealth.WORKING }
        .sortedWith(
            compareByDescending<AwgWarpProfileSummary> { it.selected }
                .thenByDescending { it.metadata.createdAtMs },
        )
        .toList()
}
