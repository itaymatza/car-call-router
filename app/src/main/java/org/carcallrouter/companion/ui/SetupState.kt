package org.carcallrouter.companion.ui

enum class SetupPhase {
    NEEDS_RUNTIME_PERMISSIONS,
    NEEDS_TELECOM_AUTHORIZATION,
    NEEDS_TARGET_DEVICE,
    READY,
    ACTIVE
}

data class SetupState(
    val runtimePermissionsGranted: Boolean,
    val telecomAuthorized: Boolean,
    val targetSelected: Boolean,
    val automationEnabled: Boolean
) {
    val completedSteps: Int = listOf(
        runtimePermissionsGranted,
        telecomAuthorized,
        targetSelected
    ).count { it }

    val ready: Boolean = completedSteps == REQUIRED_STEPS

    val phase: SetupPhase = when {
        !runtimePermissionsGranted -> SetupPhase.NEEDS_RUNTIME_PERMISSIONS
        !telecomAuthorized -> SetupPhase.NEEDS_TELECOM_AUTHORIZATION
        !targetSelected -> SetupPhase.NEEDS_TARGET_DEVICE
        automationEnabled -> SetupPhase.ACTIVE
        else -> SetupPhase.READY
    }

    companion object {
        const val REQUIRED_STEPS = 3
    }
}
