package com.v2ray.ang.ui.main

import com.v2ray.ang.core.ServiceCheckRunner
import com.v2ray.ang.dto.entities.ServiceCheckReport
import com.v2ray.ang.dto.entities.ServiceCheckResult
import com.v2ray.ang.dto.entities.ServiceCheckStatus

/**
 * State of one chip of a server row.
 *
 * The chip is only shown when the state is not [HIDDEN].
 */
internal enum class ServiceCheckChip {
    /** The chip is not shown at all. */
    HIDDEN,

    /** The check succeeded, the chip is green. */
    OK,

    /** The service is not available, the chip is red. */
    NO,

    /** The exit IP is blocked, the chip is orange. */
    IPBLOCK,

    /** The check could not answer, the chip is grey. */
    UNKNOWN,
}

/**
 * The service check data of one row of the server list.
 *
 * The model holds only what the row shows, the full report stays in the service check
 * store and is read when the details are opened.
 *
 * @param country The two letter country code of the exit IP, empty when it is unknown.
 * @param ytChip The state of the "YT" chip.
 * @param musicChip The state of the "M" chip.
 */
internal data class ServerServiceCheckUiModel(
    val country: String = "",
    val ytChip: ServiceCheckChip = ServiceCheckChip.HIDDEN,
    val musicChip: ServiceCheckChip = ServiceCheckChip.HIDDEN,
) {
    /** Whether the row shows nothing of the service check. */
    val isEmpty: Boolean
        get() = country.isEmpty() &&
                ytChip == ServiceCheckChip.HIDDEN &&
                musicChip == ServiceCheckChip.HIDDEN
}

/**
 * One check of the details dialog.
 *
 * @param checkId The id of the check, for example `youtube_premium`.
 * @param status The outcome of the check.
 * @param value The value the check found, for example the country code.
 * @param reason The reason of a failure.
 * @param httpCode The HTTP status code of the answer.
 * @param locationHeader The `Location` header of the answer.
 * @param bodyPreview The first bytes of the answer body.
 * @param fullBody The whole captured body of the check, it is the body preview for every
 * check except the YouTube Music check, which keeps its own full body.
 */
data class ServiceCheckDetailsCheckUiModel(
    val checkId: String,
    val status: ServiceCheckStatus,
    val value: String? = null,
    val reason: String? = null,
    val httpCode: Int? = null,
    val locationHeader: String? = null,
    val bodyPreview: String? = null,
    val fullBody: String? = null,
)

/**
 * The service check report of one profile, as the details dialog shows it.
 *
 * @param remarks The remarks of the profile.
 * @param timestamp The time of the run in milliseconds.
 * @param error The error of the run, it is null when the run itself worked.
 * @param checks The result of every check that ran.
 */
data class ServiceCheckDetailsUiModel(
    val remarks: String,
    val timestamp: Long = 0L,
    val error: String? = null,
    val checks: List<ServiceCheckDetailsCheckUiModel> = emptyList(),
) {
    /**
     * The body that the copy button of a check copies.
     */
    fun bodyOf(checkId: String): String? =
        checks.firstOrNull { it.checkId == checkId }?.fullBody
}

/**
 * Builds the row data of a service check report.
 *
 * A profile that was never checked has no report, so its row shows nothing. A profile
 * with an error shows a grey chip, the other chips stay hidden.
 */
internal fun ServiceCheckReport.toRowUiModel(): ServerServiceCheckUiModel {
    val premium = result(ServiceCheckRunner.CHECK_YOUTUBE_PREMIUM)
    val captcha = result(ServiceCheckRunner.CHECK_GOOGLE_SEARCH_CAPTCHA)
    val music = result(ServiceCheckRunner.CHECK_YOUTUBE_MUSIC)
    val ytChip = when {
        premium == null -> ServiceCheckChip.UNKNOWN
        premium.status == ServiceCheckStatus.NO -> ServiceCheckChip.NO
        captcha?.status == ServiceCheckStatus.IPBLOCK -> ServiceCheckChip.IPBLOCK
        ServiceCheckRunner.isYtOk(this) -> ServiceCheckChip.OK
        else -> ServiceCheckChip.UNKNOWN
    }
    val country = result(ServiceCheckRunner.CHECK_YOUTUBE_COUNTRY)?.value?.takeIf { it.isNotBlank() }
        ?: result(ServiceCheckRunner.CHECK_GOOGLE_COUNTRY)?.value?.takeIf { it.isNotBlank() }
        ?: ""
    val musicChip = when (music?.status) {
        ServiceCheckStatus.YES -> ServiceCheckChip.OK
        ServiceCheckStatus.NO -> ServiceCheckChip.NO
        else -> ServiceCheckChip.HIDDEN
    }
    return ServerServiceCheckUiModel(
        country = country,
        ytChip = ytChip,
        musicChip = musicChip,
    )
}

/**
 * Builds the data of the details dialog from a service check report.
 */
internal fun ServiceCheckReport.toDetailsUiModel(remarks: String): ServiceCheckDetailsUiModel =
    ServiceCheckDetailsUiModel(
        remarks = remarks,
        timestamp = timestamp,
        error = error,
        checks = results.map { it.toDetailsCheckUiModel(this) },
    )

/**
 * Builds the data of one check of the details dialog.
 *
 * The YouTube Music check keeps its own captured body, every other check only has the
 * body preview.
 */
private fun ServiceCheckResult.toDetailsCheckUiModel(
    report: ServiceCheckReport,
): ServiceCheckDetailsCheckUiModel = ServiceCheckDetailsCheckUiModel(
    checkId = checkId,
    status = status,
    value = value,
    reason = reason,
    httpCode = httpCode,
    locationHeader = locationHeader,
    bodyPreview = bodyPreview,
    fullBody = if (checkId == ServiceCheckRunner.CHECK_YOUTUBE_MUSIC) report.musicBody else bodyPreview,
)
