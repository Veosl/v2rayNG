package com.v2ray.ang.ui.servicecheck

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.SERVICE_CHECK_CONCURRENCY
import com.v2ray.ang.core.ServiceCheckRunner
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.base.BaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Runs the service checks of the profiles of the selected subscription group.
 *
 * The results only live in memory, they are gone when the screen is closed.
 */
class ServiceCheckViewModel(application: Application) : BaseViewModel(application) {

    private val _uiState = MutableStateFlow(ServiceCheckUiState())
    val uiState: StateFlow<ServiceCheckUiState> = _uiState.asStateFlow()

    private var checkJob: Job? = null

    fun onAction(action: ServiceCheckAction) {
        when (action) {
            ServiceCheckAction.Start -> start()
            ServiceCheckAction.Cancel -> cancel()
        }
    }

    /**
     * Starts the check of every profile that has a real delay result.
     *
     * A previous run is stopped and awaited first, so two runs never check the same
     * config at the same time.
     */
    private fun start() {
        if (checkJob?.isActive == true) return
        val previousJob = checkJob
        checkJob = viewModelScope.launch {
            previousJob?.cancelAndJoin()
            val profiles = withContext(Dispatchers.IO) { loadProfiles() }
            if (profiles.isEmpty()) {
                _uiState.value = ServiceCheckUiState(
                    message = getString(
                        R.string.service_check_hint_no_tested_configs,
                        getString(R.string.title_real_ping_all_server),
                    )
                )
                return@launch
            }
            _uiState.value = ServiceCheckUiState(
                isRunning = true,
                total = profiles.size,
                rows = profiles.map { ServiceCheckRow(guid = it.guid, remark = it.remark) },
            )
            try {
                coroutineScope {
                    val semaphore = Semaphore(SERVICE_CHECK_CONCURRENCY)
                    profiles.map { profile ->
                        async {
                            semaphore.withPermit { checkProfile(profile) }
                        }
                    }.awaitAll()
                }
            } finally {
                _uiState.update { it.copy(isRunning = false) }
            }
        }
    }

    /**
     * Cancels the running check, the temporary cores are stopped by the runner.
     *
     * The job reference is kept, so a later start waits for the cancelled run to
     * release its cores.
     */
    private fun cancel() {
        checkJob?.cancel()
    }

    /**
     * Reads the profiles of the selected group that have a real delay result.
     *
     * An empty group id is the group that shows the servers of every subscription.
     */
    private fun loadProfiles(): List<ServiceCheckProfile> {
        val subscriptionId = MmkvManager
            .decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID, "")
            .orEmpty()
        val guids = if (subscriptionId.isEmpty()) {
            MmkvManager.decodeAllServerList()
        } else {
            MmkvManager.decodeServerList(subscriptionId)
        }
        return guids.mapNotNull { guid ->
            val delayMillis = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
            if (delayMillis <= 0L) return@mapNotNull null
            val remark = MmkvManager.decodeServerConfig(guid)?.remarks.orEmpty()
            ServiceCheckProfile(guid = guid, remark = remark)
        }
    }

    /**
     * Checks one profile and publishes its result.
     */
    private suspend fun checkProfile(profile: ServiceCheckProfile) {
        val report = ServiceCheckRunner.check(app, profile.guid)
        _uiState.update { state ->
            state.copy(
                completed = state.completed + 1,
                rows = state.rows.map { row ->
                    if (row.guid != profile.guid) {
                        row
                    } else {
                        row.copy(
                            exitIp = report.result(ServiceCheckRunner.CHECK_EXIT_IP)?.value,
                            premiumStatus = report.result(ServiceCheckRunner.CHECK_YOUTUBE_PREMIUM)?.status,
                            error = report.error,
                        )
                    }
                },
            )
        }
    }
}
