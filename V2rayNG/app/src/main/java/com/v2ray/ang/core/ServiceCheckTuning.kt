package com.v2ray.ang.core

/**
 * The tuning values of the service check and of the real-delay pipeline.
 *
 * Every number of the pipeline lives here, so it can be changed in one place. The
 * values are the starting guesses, tune them after testing.
 */
object ServiceCheckTuning {

    // starting guesses, tune after testing

    /**
     * The number of real-delay tests that run at the same time in a pipeline run.
     *
     * A pipeline run sends this number with the start message, so the number of
     * temporary cores stays inside [MAX_TEMP_CORES_ALIVE]. The user setting itself is
     * never changed and a normal test still uses the user setting.
     */
    const val REAL_DELAY_CONCURRENCY_PIPELINE = 12

    /** The number of service checks that run at the same time in a pipeline run. */
    const val YT_CONCURRENCY_PIPELINE = 4

    /** The number of service checks that run at the same time in a standalone run. */
    const val YT_CONCURRENCY_STANDALONE = 6

    /**
     * The number of temporary cores that may be alive at the same time.
     *
     * A pipeline run uses [REAL_DELAY_CONCURRENCY_PIPELINE] real-delay tests and
     * [YT_CONCURRENCY_PIPELINE] service checks at the same time.
     */
    const val MAX_TEMP_CORES_ALIVE = 16

    /**
     * The number of finished checks that are kept for the screen.
     *
     * A worker never waits for the screen, the oldest event is dropped when the buffer
     * is full. The buffer is much bigger than the number of configs that can finish in
     * one flush interval of the screen.
     */
    const val SERVICE_CHECK_EVENT_BUFFER = 256

    /**
     * The percent a candidate must be faster than the connected config before the
     * automatic run switches to it.
     *
     * The percent and the milliseconds are both needed: a small delay needs a bigger
     * percent, a big delay needs more milliseconds.
     */
    const val SWITCH_MIN_IMPROVEMENT_PERCENT = 30

    /**
     * The milliseconds a candidate must be faster than the connected config before the
     * automatic run switches to it.
     */
    const val SWITCH_MIN_IMPROVEMENT_MS = 50L

    /**
     * The milliseconds between two switches of the automatic run.
     *
     * The first connection of a run counts as a switch, so the next switch waits for
     * this time.
     */
    const val SWITCH_MIN_INTERVAL_MS = 10_000L

    /**
     * The number of switches of one automatic run.
     *
     * The first connection of a run counts as the first switch.
     */
    const val SWITCH_MAX_PER_RUN = 3

    init {
        require(REAL_DELAY_CONCURRENCY_PIPELINE + YT_CONCURRENCY_PIPELINE <= MAX_TEMP_CORES_ALIVE) {
            "A pipeline run must not use more than $MAX_TEMP_CORES_ALIVE temporary cores at the same time"
        }
        require(YT_CONCURRENCY_PIPELINE > 0 && YT_CONCURRENCY_STANDALONE > 0) {
            "Every run needs at least one worker"
        }
    }
}
