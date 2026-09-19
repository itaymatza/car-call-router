package org.carcallrouter.companion.telecom

import android.os.OutcomeReceiver
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import org.carcallrouter.companion.core.EndpointIdentity

/**
 * API 34+ Telecom endpoint boundary. Requests use only live CallEndpoint instances supplied by
 * Telecom. Endpoint identifiers are session-scoped and are never persisted as device identity.
 */
class AddressedTelecomRouter(private val service: InCallService) {
    data class Target(
        val endpoint: CallEndpoint?,
        val reason: String,
        val basis: EndpointIdentity.Basis? = null
    )

    private var available: List<CallEndpoint> = emptyList()
    private var availableSnapshotReceived = false
    private var current: CallEndpoint? = null
    // API 37 may report the request before or after the resulting endpoint change. Counts are
    // consumed only by onCallEndpointRequested, never by onCallEndpointChanged.
    private val ownRequestCounts = mutableMapOf<String, Int>()

    fun updateAvailable(endpoints: List<CallEndpoint>) {
        availableSnapshotReceived = true
        available = endpoints.toList()
        val ids = available.map { it.identifier.toString() }.toSet()
        if (current?.identifier?.toString() !in ids) current = null
    }

    fun updateCurrent(endpoint: CallEndpoint) {
        current = endpoint
    }

    fun current(): CallEndpoint? {
        current?.let { return it }
        return try {
            service.currentCallEndpoint.takeIf { endpoint ->
                !availableSnapshotReceived || available.any { it.identifier == endpoint.identifier }
            }?.also { current = it }
        } catch (_: RuntimeException) { null }
    }

    fun hasAvailableSnapshot(): Boolean = availableSnapshotReceived

    fun target(
        savedLabel: String,
        targetHfpConnected: Boolean,
        connectedHfpCount: Int
    ): Target {
        val endpoints = available.filter { it.endpointType == CallEndpoint.TYPE_BLUETOOTH }
        val candidates = endpoints.map {
            EndpointIdentity.Candidate(it.identifier.toString(), it.endpointName.toString())
        }
        return when (val resolution = EndpointIdentity.resolve(
            savedLabel,
            candidates,
            targetHfpConnected,
            connectedHfpCount
        )) {
            is EndpointIdentity.Resolution.Matched -> Target(
                endpoint = endpoints.single { it.identifier.toString() == resolution.candidate.id },
                reason = "Matched current Telecom endpoint",
                basis = resolution.basis
            )
            is EndpointIdentity.Resolution.Unavailable -> Target(null, resolution.reason)
        }
    }

    fun isCurrent(endpoint: CallEndpoint?): Boolean = endpoint != null &&
        current()?.identifier == endpoint.identifier

    fun consumeOwnRequest(endpoint: CallEndpoint): Boolean {
        val id = endpoint.identifier.toString()
        val count = ownRequestCounts[id] ?: return false
        if (count == 1) ownRequestCounts.remove(id) else ownRequestCounts[id] = count - 1
        return true
    }

    fun clearSession() {
        ownRequestCounts.clear()
    }

    fun request(
        endpoint: CallEndpoint,
        accepted: () -> Unit,
        rejected: (CallEndpointException) -> Unit
    ) {
        check(available.any { it.identifier == endpoint.identifier }) {
            "Endpoint is no longer in Telecom's current callback set"
        }
        val id = endpoint.identifier.toString()
        ownRequestCounts[id] = (ownRequestCounts[id] ?: 0) + 1
        try {
            service.requestCallEndpointChange(
                endpoint,
                service.mainExecutor,
                object : OutcomeReceiver<Void?, CallEndpointException> {
                    override fun onResult(result: Void?) = accepted()
                    // Keep the marker until the API 37 request callback is consumed. That callback
                    // can be delivered after this result callback.
                    override fun onError(error: CallEndpointException) = rejected(error)
                }
            )
        } catch (error: RuntimeException) {
            val count = ownRequestCounts[id] ?: 0
            if (count <= 1) ownRequestCounts.remove(id) else ownRequestCounts[id] = count - 1
            throw error
        }
    }
}
