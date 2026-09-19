package org.carcallrouter.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStateTest {
    @Test fun reportsFirstIncompleteStep() {
        assertEquals(
            SetupPhase.NEEDS_RUNTIME_PERMISSIONS,
            SetupState(false, false, false, false).phase
        )
        assertEquals(
            SetupPhase.NEEDS_TARGET_DEVICE,
            SetupState(true, false, false, false).phase
        )
        assertEquals(
            SetupPhase.NEEDS_TELECOM_AUTHORIZATION,
            SetupState(true, false, true, false).phase
        )
    }

    @Test fun distinguishesReadyFromActive() {
        val ready = SetupState(true, true, true, false)
        val active = SetupState(true, true, true, true)

        assertTrue(ready.ready)
        assertEquals(3, ready.completedSteps)
        assertEquals(SetupPhase.READY, ready.phase)
        assertTrue(active.ready)
        assertEquals(SetupPhase.ACTIVE, active.phase)
    }

    @Test fun neverReportsReadyWithMissingRequirement() {
        val state = SetupState(true, false, true, true)

        assertFalse(state.ready)
        assertEquals(2, state.completedSteps)
        assertEquals(SetupPhase.NEEDS_TELECOM_AUTHORIZATION, state.phase)
    }
}
