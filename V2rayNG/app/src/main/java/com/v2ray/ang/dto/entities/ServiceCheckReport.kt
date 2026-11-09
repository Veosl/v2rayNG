package com.v2ray.ang.dto.entities

/**
 * Result of one service check.
 *
 * The names are the status names of `ipregion.sh` in upper case, they are stored as
 * they are in the service check store.
 */
enum class ServiceCheckStatus {
    YES,
    NO,
    IPBLOCK,
    UNKNOWN
}

/**
 * Result of one service check.
 *
 * @param checkId The id of the check, for example `youtube_premium`.
 * @param status The outcome of the check.
 * @param value The value the check found, for example the country code.
 * @param reason The reason of a failure, it is null when the check succeeded.
 * @param httpCode The HTTP status code of the answer.
 * @param locationHeader The `Location` header of the answer, redirects are not followed.
 * @param bodyPreview The first bytes of the answer body, it is used by the details view.
 */
data class ServiceCheckResult(
    val checkId: String,
    val status: ServiceCheckStatus,
    val value: String? = null,
    val reason: String? = null,
    val httpCode: Int? = null,
    val locationHeader: String? = null,
    val bodyPreview: String? = null,
)

/**
 * Results of every check of one config.
 *
 * @param guid The GUID of the profile, it is the key of the service check store.
 * @param timestamp The time of the run in milliseconds.
 * @param results The result of every check that ran.
 * @param error The error of the run, it is null when the run itself worked.
 * @param musicBody The full captured body of the YouTube Music check, it is kept apart
 * from the body previews because the user needs the whole text to find the
 * "not available in your area" message.
 */
data class ServiceCheckReport(
    val guid: String,
    val timestamp: Long = 0L,
    val results: List<ServiceCheckResult> = emptyList(),
    val error: String? = null,
    val musicBody: String? = null,
) {
    fun result(checkId: String): ServiceCheckResult? = results.firstOrNull { it.checkId == checkId }
}
