package com.v2ray.ang.dto

import java.io.Serializable

/**
 * The message that starts or stops a measurement in the `:tasks` process.
 *
 * @param key The message key, see [com.v2ray.ang.AppConfig].
 * @param subscriptionId The id of the group that is measured.
 * @param serverGuids The GUIDs of the configs that are measured, empty means the whole
 * group.
 * @param onlyTcp Whether only the TCP delay is measured.
 * @param concurrency The number of tests that run at the same time. Zero means the
 * user setting is used, a positive number is an override for this run only. The user
 * setting itself is never changed.
 */
data class TestServiceMessage(
    val key: Int,
    val subscriptionId: String = "",
    val serverGuids: List<String> = emptyList(),
    val onlyTcp: Boolean = false,
    val concurrency: Int = 0
) : Serializable {

    companion object {
        /**
         * The version of the serialized form.
         *
         * The message travels between two processes of the same app, both sides always
         * run the same code. The id is fixed, so a new field does not change it.
         */
        private const val serialVersionUID: Long = 1L
    }
}
