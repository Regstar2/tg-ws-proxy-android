package com.amurcanov.tgwsproxy

class NetworkProfileChangeDetector(
    initialProfile: NetworkProfile? = null,
) {
    private var previous: NetworkProfile? = initialProfile

    fun shouldNotify(newProfile: NetworkProfile): Boolean {
        val old = previous
        previous = newProfile
        if (old == null) {
            return false
        }
        return old.id != newProfile.id || old.type != newProfile.type
    }
}
