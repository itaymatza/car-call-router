package org.carcallrouter.companion.core

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class PolicyTest(
    private val name: String,
    private val body: () -> Unit,
) {
    @Test fun policyCase() = body()

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): Collection<Array<Any>> = PolicyCases.cases().map { arrayOf<Any>(it.first, it.second) }
    }
}
