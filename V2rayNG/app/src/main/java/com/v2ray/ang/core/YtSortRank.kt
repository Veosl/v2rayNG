package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ServiceCheckReport
import com.v2ray.ang.dto.entities.ServiceCheckStatus

/**
 * Ranks the configs by their YouTube result.
 *
 * The object is pure Kotlin and imports no Android class: it only reads the values it
 * is given and asks [ServiceCheckRunner] for the "YT OK" verdict. It owns the order of
 * the ranks, `AngConfigManager` owns the persistence of the new order.
 *
 * A lower rank is better.
 */
object YtSortRank {

    /** The config is usable for YouTube. */
    const val RANK_YT_OK = 0

    /** The config has a real delay, it was not checked yet. */
    const val RANK_DELAY_NOT_CHECKED = 1

    /** The config was checked, the result is unknown or the run failed. */
    const val RANK_CHECKED_UNKNOWN = 2

    /** The exit IP gets a captcha on the Google search. */
    const val RANK_CAPTCHA_IPBLOCK = 3

    /** YouTube Premium is not offered for the exit IP. */
    const val RANK_PREMIUM_NO = 4

    /** The config has no real delay, the delay test failed or it was never run. */
    const val RANK_NO_DELAY = 5

    /**
     * The rank of one config.
     *
     * @param report The report of the service check, null when the config was never
     * checked.
     * @param delayMillis The real delay of the config, zero when it has none.
     * @return The rank of the config, lower is better.
     */
    fun rank(report: ServiceCheckReport?, delayMillis: Long): Int {
        if (report == null) {
            return if (delayMillis > 0L) RANK_DELAY_NOT_CHECKED else RANK_NO_DELAY
        }
        if (ServiceCheckRunner.isYtOk(report)) {
            return RANK_YT_OK
        }
        val captcha = report.result(ServiceCheckRunner.CHECK_GOOGLE_SEARCH_CAPTCHA)
        if (captcha?.status == ServiceCheckStatus.IPBLOCK) {
            return RANK_CAPTCHA_IPBLOCK
        }
        val premium = report.result(ServiceCheckRunner.CHECK_YOUTUBE_PREMIUM)
        if (premium?.status == ServiceCheckStatus.NO) {
            return RANK_PREMIUM_NO
        }
        return RANK_CHECKED_UNKNOWN
    }

    /**
     * Sorts the configs by their YouTube result.
     *
     * Inside one rank the fastest config comes first, a config without a delay is the
     * slowest one. The order of the configs with the same rank and the same delay is
     * the order of the given list.
     *
     * @param guids The GUIDs of the configs, in their current order.
     * @param delayOf The real delay of a config.
     * @param reportOf The service check report of a config, null when it was never
     * checked.
     * @return The GUIDs in their new order.
     */
    fun sort(
        guids: List<String>,
        delayOf: (String) -> Long,
        reportOf: (String) -> ServiceCheckReport?,
    ): List<String> {
        val ranked = guids.map { guid ->
            val delay = delayOf(guid)
            Triple(
                guid,
                rank(reportOf(guid), delay),
                if (delay > 0L) delay else Long.MAX_VALUE,
            )
        }
        return ranked.sortedWith(
            compareBy<Triple<String, Int, Long>> { it.second }.thenBy { it.third }
        ).map { it.first }
    }
}
