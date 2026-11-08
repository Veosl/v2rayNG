package com.v2ray.ang.ui.servicecheck

import androidx.annotation.StringRes
import com.v2ray.ang.R
import com.v2ray.ang.core.ServiceCheckStatus

/** One profile that is checked, identified by its GUID. */
data class ServiceCheckProfile(
    val guid: String,
    val remark: String,
)

/** One row of the result list. */
data class ServiceCheckRow(
    val guid: String,
    val remark: String,
    val exitIp: String? = null,
    val premiumStatus: ServiceCheckStatus? = null,
    val error: String? = null,
)

/** State of the service check screen. */
data class ServiceCheckUiState(
    val isRunning: Boolean = false,
    val total: Int = 0,
    val completed: Int = 0,
    val rows: List<ServiceCheckRow> = emptyList(),
    val message: String? = null,
)

/** All user intents of the service check screen. */
sealed interface ServiceCheckAction {
    data object Start : ServiceCheckAction
    data object Cancel : ServiceCheckAction
}

/** Localized label of a check status. */
@StringRes
fun ServiceCheckStatus.labelRes(): Int = when (this) {
    ServiceCheckStatus.YES -> R.string.service_check_status_yes
    ServiceCheckStatus.NO -> R.string.service_check_status_no
    ServiceCheckStatus.IPBLOCK -> R.string.service_check_status_ipblock
    ServiceCheckStatus.UNKNOWN -> R.string.service_check_status_unknown
}
