package org.carcallrouter.companion.core

/**
 * Produces stable, machine-readable, privacy-safe routing evidence.
 *
 * Callers must pass only enums, booleans, numbers, or already-redacted identifiers. Values are
 * tokenized again here so accidental whitespace or log delimiters cannot corrupt the record.
 */
class RoutingTrace(
    private val now: () -> Long,
    private val newSessionId: () -> String,
    private val emit: (String) -> Unit,
) {
    enum class Confirmation { NONE, TELECOM_ENDPOINT, TARGET_HFP_AUDIO }

    private var sessionId: String? = null
    private var startedAt = 0L
    private var sequence = 0
    private var confirmation = Confirmation.NONE

    fun begin(
        mode: String,
        trigger: String,
    ) {
        if (sessionId != null) return
        sessionId = token(newSessionId())
        startedAt = now()
        sequence = 0
        confirmation = Confirmation.NONE
        event("SESSION_STARTED", "mode" to mode, "trigger" to trigger)
    }

    fun event(
        name: String,
        vararg fields: Pair<String, Any?>,
    ) {
        val id = sessionId ?: return
        val elapsed = (now() - startedAt).coerceAtLeast(0)
        val body =
            buildList {
                add("schema=1")
                add("session=$id")
                add("seq=${++sequence}")
                add("elapsed_ms=$elapsed")
                add("event=${token(name)}")
                fields.forEach { (key, value) -> add("${token(key)}=${token(value?.toString() ?: "null")}") }
            }.joinToString(" ")
        emit(body)
    }

    fun confirmTelecom() {
        if (confirmation != Confirmation.NONE) return
        confirmation = Confirmation.TELECOM_ENDPOINT
        event("TELECOM_ENDPOINT_CONFIRMED")
    }

    fun confirmTargetHfpAudio() {
        if (confirmation == Confirmation.TARGET_HFP_AUDIO) return
        if (confirmation == Confirmation.NONE) confirmTelecom()
        confirmation = Confirmation.TARGET_HFP_AUDIO
        event("TARGET_HFP_AUDIO_CONFIRMED")
    }

    fun finish(
        phase: RoutingPolicy.Phase,
        reason: RoutingPolicy.ReasonCode,
        termination: String,
    ): Confirmation? {
        if (sessionId == null) return null
        val result = confirmation
        event(
            "SESSION_FINISHED",
            "phase" to phase,
            "reason" to reason,
            "termination" to termination,
            "best_confirmation" to confirmation,
        )
        sessionId = null
        return result
    }

    fun isActive(): Boolean = sessionId != null

    private fun token(value: String): String =
        value
            .trim()
            .replace(Regex("[^A-Za-z0-9_.:-]"), "_")
            .take(96)
            .ifEmpty { "empty" }
}
