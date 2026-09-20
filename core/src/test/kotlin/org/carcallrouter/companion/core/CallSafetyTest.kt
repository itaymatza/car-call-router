package org.carcallrouter.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallSafetyTest {
    private val safe = CallSafety.Evidence(true, false, false, false, false, true, false)

    @Test
    fun ordinarySimCallIsAccepted() {
        assertNull(CallSafety.rejection(safe))
    }

    @Test
    fun everyUnsafeBoundaryReturnsItsStableReason() {
        val cases =
            listOf(
                safe.copy(emergencyFlag = true) to "Network-identified emergency call",
                safe.copy(emergencyCallbackMode = true) to "Emergency callback mode",
                safe.copy(externalOrSelfManaged = true) to "External or self-managed call",
                safe.copy(conference = true) to "Conference call",
                safe.copy(simAccount = null) to "SIM-backed phone account not verified",
                safe.copy(simAccount = false) to "SIM-backed phone account not verified",
                safe.copy(telephoneHandlePresent = false) to
                    "Telephone handle hidden or unavailable; emergency status unknown",
                safe.copy(numberIsEmergency = null) to "Emergency-number classification unavailable",
                safe.copy(numberIsEmergency = true) to "Emergency number",
            )
        cases.forEach { (evidence, expected) -> assertEquals(expected, CallSafety.rejection(evidence)) }
    }

    @Test
    fun rejectionPrecedenceNeverHidesEmergencyEvidence() {
        val allUnsafe = CallSafety.Evidence(null, true, true, true, true, false, true)
        assertEquals("Network-identified emergency call", CallSafety.rejection(allUnsafe))
        assertEquals(
            "Emergency callback mode",
            CallSafety.rejection(allUnsafe.copy(emergencyFlag = false)),
        )
    }
}
