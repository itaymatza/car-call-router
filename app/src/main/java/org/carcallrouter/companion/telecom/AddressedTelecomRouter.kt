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
    private var current: CallEndpoint? = null
    private var pendingRequestId: String? = null

    fun updateAvailable(endpoints: List<CallEndpoint>) {
        available = endpoints.toList()
        val ids = available.map { it.identifier.toString() }.toSet()
        if (pendingRequestId !in ids) pendingRequestId = null
    }

    fun updateCurrent(endpoint: CallEndpoint) {
        current = endpoint
        if (pendingRequestId == endpoint.identifier.toString()) pendingRequestId = null
    }

    fun current(): CallEndpoint? = current ?: try {
        service.currentCallEndpoint.also { current = it }
    } catch (_: RuntimeException) { null }

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

    fun isOwnPendingRequest(endpoint: CallEndpoint): Boolean =
        pendingRequestId == endpoint.identifier.toString()

    fun request(
        endpoint: CallEndpoint,
        accepted: () -> Unit,
        rejected: (CallEndpointException) -> Unit
    ) {
        check(available.any { it.identifier == endpoint.identifier }) {
            "Endpoint is no longer in Telecom's current callback set"
        }
        pendingRequestId = endpoint.identifier.toString()
        service.requestCallEndpointChange(
            endpoint,
            service.mainExecutor,
            object : OutcomeReceiver<Void?, CallEndpointException> {
                override fun onResult(result: Void?) = accepted()
                override fun onError(error: CallEndpointException) {
                    pendingRequestId = null
                    rejected(error)
                }
            }
        )
    }
}
