package com.v2ray.ang.core

/**
 * The decision of the automatic run whether it switches to a faster config.
 *
 * The class is pure Kotlin: it has no Android dependency, it changes no state and it
 * reads no store. The thresholds live in [ServiceCheckTuning].
 */
object FastestSwitchDecision {

    /** The result of one decision. */
    enum class Decision {
        /** The run connects the candidate. */
        SWITCH,

        /** The run keeps the connected config. */
        STAY,
    }

    /**
     * The values of one decision.
     *
     * @param connectedGuid The GUID of the connected config, null when the run is not
     * connected to a config of its group.
     * @param connectedDelay The delay of the connected config in the running test, null
     * when the result of the connected config has not arrived yet.
     * @param candidateGuid The GUID of the candidate.
     * @param candidateDelay The delay of the candidate in the running test.
     * @param millisSinceLastSwitch The milliseconds since the last switch of the run, null
     * when the run did not switch yet.
     * @param switchCount The number of switches of the run.
     */
    data class Input(
        val connectedGuid: String?,
        val connectedDelay: Long?,
        val candidateGuid: String,
        val candidateDelay: Long,
        val millisSinceLastSwitch: Long?,
        val switchCount: Int,
    )

    /**
     * Decides whether the run switches to the candidate.
     *
     * A candidate beats the connected config only when it is at least
     * [ServiceCheckTuning.SWITCH_MIN_IMPROVEMENT_PERCENT] percent and at least
     * [ServiceCheckTuning.SWITCH_MIN_IMPROVEMENT_MS] milliseconds faster. The number of
     * switches of the run and the time since the last switch are checked here too.
     *
     * @param input The values of the decision.
     * @return [Decision.SWITCH] when the run connects the candidate, [Decision.STAY]
     * otherwise.
     */
    fun decide(input: Input): Decision {
        // A config without a delay result is not a candidate.
        if (input.candidateDelay <= 0L) return Decision.STAY
        // The connected config is not a candidate.
        if (input.connectedGuid != null && input.connectedGuid == input.candidateGuid) {
            return Decision.STAY
        }
        // The number of switches of the run is limited.
        if (input.switchCount >= ServiceCheckTuning.SWITCH_MAX_PER_RUN) return Decision.STAY
        // Two switches are at least SWITCH_MIN_INTERVAL_MS apart.
        val millisSinceLastSwitch = input.millisSinceLastSwitch
        if (millisSinceLastSwitch != null &&
            millisSinceLastSwitch < ServiceCheckTuning.SWITCH_MIN_INTERVAL_MS
        ) {
            return Decision.STAY
        }
        // The delay of the connected config is the base of the comparison. Without it
        // there is nothing to compare.
        val connectedDelay = input.connectedDelay ?: return Decision.STAY
        // The connected config failed its test: every working config is better.
        if (connectedDelay <= 0L) return Decision.SWITCH
        // The candidate must be at least SWITCH_MIN_IMPROVEMENT_MS faster.
        val improvement = connectedDelay - input.candidateDelay
        if (improvement < ServiceCheckTuning.SWITCH_MIN_IMPROVEMENT_MS) return Decision.STAY
        // The candidate must be at least SWITCH_MIN_IMPROVEMENT_PERCENT faster.
        if (improvement * 100L <
            connectedDelay * ServiceCheckTuning.SWITCH_MIN_IMPROVEMENT_PERCENT
        ) {
            return Decision.STAY
        }
        return Decision.SWITCH
    }
}
