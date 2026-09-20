package org.carcallrouter.companion.core

import org.carcallrouter.companion.core.EndpointIdentity.Basis
import org.carcallrouter.companion.core.EndpointIdentity.Candidate
import org.carcallrouter.companion.core.EndpointIdentity.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointIdentityTest {
    private val target = Candidate("endpoint-a", "Car Hands-Free")
    private val other = Candidate("endpoint-b", "Projection Unit")

    @Test fun uniqueNameMatchWinsWithMultipleConnectedDevices() {
        val result = EndpointIdentity.resolve("  CAR  hands-free ", listOf(target, other), true, 2)
        assertTrue(result is Resolution.Matched)
        result as Resolution.Matched
        assertEquals(target, result.candidate)
        assertEquals(Basis.UNIQUE_LABEL, result.basis)
    }

    @Test fun duplicateNamesFailClosed() {
        val result =
            EndpointIdentity.resolve(
                "Car Hands-Free",
                listOf(target, Candidate("endpoint-c", "car hands-free")),
                true,
                2,
            )
        assertTrue(result is Resolution.Unavailable)
    }

    @Test fun disconnectedTargetFailsClosed() {
        assertTrue(EndpointIdentity.resolve(target.label, listOf(target), false, 0) is Resolution.Unavailable)
    }

    @Test fun singleEndpointAndSingleHfpCanResolveOemRenamedEndpoint() {
        val result = EndpointIdentity.resolve("Saved alias", listOf(target), true, 1)
        assertTrue(result is Resolution.Matched)
        assertEquals(Basis.SINGLE_CONNECTED_HFP, (result as Resolution.Matched).basis)
    }

    @Test fun unknownNameWithMultipleEndpointsFailsClosed() {
        assertTrue(
            EndpointIdentity.resolve("Unknown", listOf(target, other), true, 2) is Resolution.Unavailable,
        )
    }

    @Test fun noTelecomEndpointFailsClosed() {
        assertTrue(EndpointIdentity.resolve(target.label, emptyList(), true, 1) is Resolution.Unavailable)
    }
}
