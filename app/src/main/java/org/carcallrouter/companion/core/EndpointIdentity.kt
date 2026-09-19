package org.carcallrouter.companion.core

/** Pure, fail-closed matching of a saved Bluetooth target to live Telecom endpoints. */
object EndpointIdentity {
    data class Candidate(val id: String, val label: String)
    sealed interface Resolution {
        data class Matched(val candidate: Candidate, val basis: Basis) : Resolution
        data class Unavailable(val reason: String) : Resolution
    }
    enum class Basis { UNIQUE_LABEL, SINGLE_CONNECTED_HFP }

    fun resolve(
        savedLabel: String,
        candidates: List<Candidate>,
        targetHfpConnected: Boolean,
        connectedHfpCount: Int
    ): Resolution {
        if (!targetHfpConnected) return Resolution.Unavailable("Selected device is not connected for calls")
        if (candidates.isEmpty()) return Resolution.Unavailable("Telecom has not offered a Bluetooth call endpoint")

        val normalized = normalize(savedLabel)
        val labelMatches = if (normalized.isEmpty()) emptyList() else candidates.filter {
            normalize(it.label) == normalized
        }
        if (labelMatches.size == 1) {
            return Resolution.Matched(labelMatches.single(), Basis.UNIQUE_LABEL)
        }
        if (labelMatches.size > 1) {
            return Resolution.Unavailable("Multiple Bluetooth call endpoints have the selected device name")
        }
        if (candidates.size == 1 && connectedHfpCount == 1) {
            return Resolution.Matched(candidates.single(), Basis.SINGLE_CONNECTED_HFP)
        }
        return Resolution.Unavailable("The selected Bluetooth device cannot be mapped uniquely to a Telecom endpoint")
    }

    private fun normalize(value: String): String = value.trim().lowercase()
        .replace(Regex("\\s+"), " ")
}
