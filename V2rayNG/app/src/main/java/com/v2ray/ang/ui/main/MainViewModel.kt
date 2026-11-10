package com.v2ray.ang.ui.main

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.ServiceCheckManager
import com.v2ray.ang.core.ServiceCheckRunState
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.LocateTarget
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.dto.entities.ServiceCheckReport
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.extension.delay
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.matchesPattern
import com.v2ray.ang.extension.moveItem
import com.v2ray.ang.handler.SubscriptionInfoParser
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.PatternSyntaxException

private fun applyTestDelayResults(
    servers: List<ServersCache>,
    updates: Map<String, Long>,
): List<ServersCache> = servers.map { server ->
    val delayMillis = updates[server.guid]
    if (delayMillis == null || delayMillis == server.testDelayMillis) {
        server
    } else {
        server.copy(testDelayMillis = delayMillis)
    }
}

private fun applyTestDelayResultsToRows(
    rows: List<ServerRowUiModel>,
    updates: Map<String, Long>,
): List<ServerRowUiModel> = rows.map { row ->
    val delayMillis = updates[row.guid]
    if (delayMillis == null || delayMillis == row.testDelayMillis) {
        row
    } else {
        row.copy(testDelayMillis = delayMillis)
    }
}

private fun applyServiceCheckReportsToRows(
    rows: List<ServerRowUiModel>,
    updates: Map<String, ServiceCheckReport>,
): List<ServerRowUiModel> = rows.map { row ->
    val report = updates[row.guid] ?: return@map row
    val serviceCheck = report.toRowUiModel()
    if (serviceCheck == row.serviceCheck) {
        row
    } else {
        row.copy(serviceCheck = serviceCheck)
    }
}

class MainViewModel(
    application: Application,
    private val dataSource: MainDataSource
) : BaseViewModel(application) {

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
    private val preloadDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    // ---------- UI state ----------
    private val _uiState = MutableStateFlow(
        MainUiState(
            selectedGroupId = dataSource.getSelectedSubscriptionId(),
            selectedGuid = dataSource.getSelectServer(),
            confirmRemove = dataSource.getConfirmRemove(),
            doubleColumnDisplay = dataSource.getDoubleColumnDisplay()
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // ---------- Keyword filtering ----------
    @Volatile
    private var keywordFilter: String = ""
    private var filterJob: Job? = null

    // ---------- Groups & cache ----------
    private val cacheMutex = Mutex()
    private val groupDataCache = mutableMapOf<String, List<ServersCache>>()
    private val groupUiFlows = ConcurrentHashMap<String, MutableStateFlow<ServerGroupUiState>>()
    private val groupServerFlows = ConcurrentHashMap<String, StateFlow<List<ServersCache>>>()
    private val groupLoadMutexes = ConcurrentHashMap<String, Mutex>()
    private val serverOrderPersistenceJobs = mutableMapOf<String, Job>()

    private var setupGroupJob: Job? = null
    private var preloadJob: Job? = null
    private var selectedGroupLoadJob: Job? = null
    private var reloadJob: Job? = null
    private var testResultFlushJob: Job? = null
    private val pendingTestResults = linkedMapOf<String, Long>()
    private var pendingTestProgress: String? = null

    private var liveSortJob: Job? = null
    private var liveSortHasNewDelayResult = false
    private var liveSortHasNewYtResult = false
    private var liveSortLastAtMs = 0L
    private var listScrolling = false

    private val testRequests = MainTestRequests()
    private var bulkTestJob: Job? = null

    // ---------- Service check ----------
    // The reports live in their own store, they are read once and kept here. The rows
    // only need the small summary of a report.
    private val serviceCheckReports = ConcurrentHashMap<String, ServiceCheckReport>()

    // The reports of the configs that finished since the last flush. They are put into
    // the rows in batches, so a burst of results does not update the screen once per
    // result.
    private val pendingServiceCheckReports = linkedMapOf<String, ServiceCheckReport>()
    private var serviceCheckFlushJob: Job? = null

    // Whether a service check run is active. The list is sorted by the YouTube result
    // while a run is active.
    @Volatile
    private var ytRunActive = false

    // The group of the running service check. The final sort uses it, so the list of
    // the group that was checked is sorted even when the user changed the group.
    // Null means that no run was seen, an empty id is the group with every server.
    private var ytRunGroupId: String? = null

    // The progress texts of the running tests. They are shown together in one status
    // line, the delay test and the service check can run at the same time.
    private var lastDelayProgress: String? = null
    private var lastYtProgress: String? = null

    // Whether this ViewModel has seen a run start. A run that was already over when the
    // screen was opened must not report a result.
    private var serviceCheckRunStarted = false

    private val initialPageReady = CompletableDeferred<Unit>()

    // ---------- Service events ----------
    init {
        collectServiceEvents()
        observeServiceCheckState()
        observeServiceCheckEvents()
        setupGroupTab()
    }

    private fun collectServiceEvents() {
        viewModelScope.launch {
            dataSource.mainServiceEvent.collect { event ->
                handleServiceEvent(event)
            }
        }
    }

    private fun handleServiceEvent(event: MainServiceEvent) {
        when (event) {
            MainServiceEvent.StateRunning -> updateRunningState(true, clearTestingText = false)
            MainServiceEvent.StateNotRunning -> updateRunningState(false, clearTestingText = false)
            MainServiceEvent.StateStartSuccess -> {
                toastSuccess(R.string.toast_services_success)
                updateRunningState(true)
            }

            is MainServiceEvent.StateStartFailure -> {
                if (!event.message.isNullOrBlank()) {
                    toastError(event.message)
                } else {
                    toastError(R.string.toast_services_failure)
                }
                updateRunningState(false)
            }

            MainServiceEvent.StateStopSuccess -> updateRunningState(false)
            is MainServiceEvent.MeasureDelayResult -> {
                if (!uiState.value.isRunning || !testRequests.completeCurrent(event.requestId)) return
                _uiState.update { it.copy(isTesting = testRequests.isTesting, status = MainStatus.ConnectionTest(event.result)) }
            }

            is MainServiceEvent.MeasureConfigSuccess -> {
                val request = testRequests.bulk?.takeIf { it.id == event.requestId } ?: return
                queueTestResult(event.result, request)
            }

            is MainServiceEvent.MeasureConfigNotify -> {
                val request = testRequests.bulk?.takeIf { it.id == event.requestId } ?: return
                queueTestProgress(event.progress, request)
            }

            is MainServiceEvent.MeasureConfigFinish -> {
                val request = testRequests.bulk?.takeIf { it.id == event.requestId } ?: return
                val scheduledFlush = testResultFlushJob
                testResultFlushJob = viewModelScope.launch {
                    scheduledFlush?.cancelAndJoin()
                    if (testRequests.bulk?.id != request.id) return@launch
                    flushPendingTestResults(request)
                    onTestsFinished(request.id)
                }
            }

            is MainServiceEvent.MeasureDelayCancelled -> {
                if (testRequests.completeCurrent(event.requestId)) resetTestStatus()
            }

            is MainServiceEvent.MeasureConfigCancelled -> {
                if (testRequests.completeBulk(event.requestId) != null) {
                    cancelPendingTestResults()
                    resetTestStatus()
                }
            }
        }
    }

    private fun queueTestResult(result: RealPingResult, request: MainTestRequests.Bulk) {
        pendingTestResults[result.guid] = result.delayMillis
        if (result.delayMillis > 0L) {
            liveSortHasNewDelayResult = true
        }
        scheduleTestResultFlush(request)
    }

    /**
     * Stores the newest progress text. It is published with the next flush, so a burst
     * of results does not update the whole screen once per result.
     */
    private fun queueTestProgress(progress: String, request: MainTestRequests.Bulk) {
        pendingTestProgress = progress
        scheduleTestResultFlush(request)
    }

    private fun scheduleTestResultFlush(request: MainTestRequests.Bulk) {
        if (testResultFlushJob?.isActive == true) return

        testResultFlushJob = viewModelScope.launch {
            while (pendingTestResults.isNotEmpty() || pendingTestProgress != null) {
                delay(TEST_RESULT_FLUSH_INTERVAL_MS)
                flushPendingTestResults(request)
            }
        }
    }

    private suspend fun flushPendingTestResults(request: MainTestRequests.Bulk) {
        if (testRequests.bulk?.id != request.id) return

        val progress = pendingTestProgress
        pendingTestProgress = null
        val updates = cacheMutex.withLock {
            val drained = pendingTestResults.toMap()
            pendingTestResults.clear()
            groupDataCache[request.groupId]?.let { cached ->
                groupDataCache[request.groupId] = applyTestDelayResults(cached, drained)
            }
            drained
        }
        if (updates.isNotEmpty() && testRequests.bulk?.id == request.id) {
            mutableServerGroupState(request.groupId).update { current ->
                current.copy(
                    servers = applyTestDelayResults(current.servers, updates),
                    rows = applyTestDelayResultsToRows(current.rows, updates),
                )
            }
        }
        if (progress != null && testRequests.bulk?.id == request.id) {
            lastDelayProgress = progress
            publishProgressText()
        }
    }

    /**
     * Publishes the progress text of the running tests.
     *
     * The delay test and the service check can run at the same time, their texts are
     * shown together in the same status line.
     */
    private fun publishProgressText() {
        val parts = listOfNotNull(lastDelayProgress, lastYtProgress)
        if (parts.isEmpty()) return
        val text = parts.joinToString(dataSource.getString(R.string.service_check_progress_separator))
        _uiState.update { it.copy(status = MainStatus.TestProgress(text)) }
    }

    internal fun formatStatus(status: MainStatus): String = when (status) {
        MainStatus.Disconnected -> dataSource.getString(R.string.connection_not_connected)
        MainStatus.Connected -> dataSource.getString(R.string.connection_connected)
        MainStatus.Testing -> dataSource.getString(R.string.connection_test_testing)
        is MainStatus.TestProgress -> dataSource.getString(
            R.string.connection_running_task_left,
            status.progress
        )

        is MainStatus.ConnectionTest -> formatConnectionTestResult(status.result)
    }

    private fun formatConnectionTestResult(result: ConnectionTestResult): String {
        val status = if (result.delayMillis >= 0) {
            val delay = dataSource.getString(R.string.server_test_delay_value, result.delayMillis)
            dataSource.getString(R.string.connection_test_available, delay)
        } else {
            val detail = result.errorMessage.ifBlank {
                dataSource.getString(R.string.connection_test_empty_message)
            }
            dataSource.getString(R.string.connection_test_error, detail)
        }

        if (result.delayMillis < 0 || (result.country == null && result.ipAddress == null)) {
            return status
        }

        val unknown = dataSource.getString(R.string.value_unknown)
        return "$status\n(${result.country ?: unknown}) ${result.ipAddress ?: unknown}"
    }

    // ---------- Public state accessors ----------
    fun serversForGroup(groupId: String): StateFlow<List<ServersCache>> =
        groupServerFlows.computeIfAbsent(groupId) {
            val groupState = mutableServerGroupState(groupId)
            groupState
                .map { it.servers }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                    initialValue = groupState.value.servers,
                )
        }

    internal fun serverGroupState(groupId: String): StateFlow<ServerGroupUiState> =
        mutableServerGroupState(groupId).asStateFlow()

    private fun mutableServerGroupState(groupId: String): MutableStateFlow<ServerGroupUiState> =
        groupUiFlows.computeIfAbsent(groupId) { MutableStateFlow(ServerGroupUiState()) }

    private fun currentServers(): List<ServersCache> =
        mutableServerGroupState(uiState.value.selectedGroupId).value.servers

    // ---------- Action handler ----------
    fun onAction(action: MainAction) {
        when (action) {
            MainAction.Initialize -> initialize()
            MainAction.RefreshGroups -> setupGroupTab(forceRefresh = true)
            MainAction.TestAllServers -> testAllRealPing(true)
            MainAction.TestRealAllServers -> testAllRealPing()
            MainAction.CancelTesting -> cancelAllPing()
            MainAction.RemoveAllServers -> removeAllServerAsync()
            MainAction.RemoveDuplicateServers -> removeDuplicateServerAsync()
            MainAction.RemoveInvalidServers -> removeInvalidServerAsync()
            MainAction.SortByTestResults -> sortByTestResultsAsync()
            MainAction.SortByYoutubeResults -> sortByYoutubeResultsAsync()
            MainAction.ClearServiceCheckResults -> clearServiceCheckResults()
            MainAction.CheckYouTubeOnTestedConfigs -> toggleYouTubeCheck()
            is MainAction.ShowServiceCheckDetails -> showServiceCheckDetails(action.guid)
            MainAction.DismissServiceCheckDetails -> dismissServiceCheckDetails()
            is MainAction.CopyServiceCheckBody -> copyServiceCheckBody(action.checkId)
            MainAction.UpdateSubscriptions -> importConfigViaSub(uiState.value.selectedGroupId)
            is MainAction.UpdateGroupSubscription -> importConfigViaSub(action.groupId)
            MainAction.ExportAll -> exportAllAsync()
            is MainAction.SelectGroup -> subscriptionIdChanged(action.groupId)
            is MainAction.SelectServer -> updateSelectedGuid(action.guid)
            is MainAction.RemoveServer -> removeServerAndRefresh(action.guid)
            is MainAction.Search -> filterConfig(action.query)
            is MainAction.SetListScrolling -> setListScrolling(action.active)
            is MainAction.ImportBatchConfig -> importBatchConfig(action.configText)
            MainAction.LocateHandled -> consumeLocateTarget()
            is MainAction.ShareQRCode -> {
                val bitmap = dataSource.share2QRCode(action.guid)
                _uiState.update { it.copy(shareQRCodeBitmap = bitmap) }
            }

            MainAction.DismissQRCodeDialog -> {
                _uiState.update { it.copy(shareQRCodeBitmap = null) }
            }

            MainAction.ToggleService,
            MainAction.TestCurrentServer,
            MainAction.ImportQRcode,
            MainAction.ImportClipboard,
            MainAction.ImportConfigLocal,
            is MainAction.ImportManually,
            MainAction.RestartService,
            MainAction.LocateSelectedServer,
            is MainAction.EditServer,
            is MainAction.ShareClipboard,
            is MainAction.ShareFullContent,
            is MainAction.OpenSubscriptionLink -> {
                // Handled by Activity via its onAction lambda
            }
        }
    }

    // ---------- Initialization ----------
    fun initialize() {
        viewModelScope.launch(preloadDispatcher) {
            try {
                initialPageReady.await()
                delay(32)
                dataSource.initAssets()
                dataSource.syncSubscriptions()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Main background initialization failed", error)
            }
        }
    }

    fun refreshUiSettings() {
        _uiState.update {
            it.copy(
                confirmRemove = dataSource.getConfirmRemove(),
                doubleColumnDisplay = dataSource.getDoubleColumnDisplay()
            )
        }
    }

    // ---------- Group & server loading ----------
    private suspend fun buildServersCache(guids: List<String>): List<ServersCache> =
        guids.mapNotNull { guid ->
            currentCoroutineContext().ensureActive()
            val profile = dataSource.decodeServerConfig(guid) ?: return@mapNotNull null
            val affiliation = dataSource.decodeAffiliationInfo(guid)
            ServersCache(
                guid = guid,
                profile = profile.copy(),
                testDelayMillis = affiliation?.testDelayMillis ?: 0L
            )
        }

    private suspend fun loadGroup(
        groupId: String,
        forceRefresh: Boolean = false
    ): List<ServersCache> {
        val loadMutex = groupLoadMutexes.computeIfAbsent(groupId) { Mutex() }
        return loadMutex.withLock {
            if (!forceRefresh) {
                cacheMutex.withLock { groupDataCache[groupId]?.let { return@withLock it } }
            }
            val servers = buildServersCache(dataSource.getServerGuidList(groupId))
            currentCoroutineContext().ensureActive()
            cacheMutex.withLock { groupDataCache[groupId] = servers }
            servers
        }
    }

    private fun applyKeywordFilter(servers: List<ServersCache>): List<ServersCache> {
        val keyword = keywordFilter.trim()
        if (keyword.isEmpty()) return servers
        val regex = try {
            Regex(keyword, RegexOption.IGNORE_CASE)
        } catch (_: PatternSyntaxException) {
            return servers
        }
        return servers.filter { cache ->
            val profile = cache.profile
            profile.remarks.matchesPattern(regex, keyword) ||
                    profile.description.orEmpty().matchesPattern(regex, keyword) ||
                    profile.server.orEmpty().matchesPattern(regex, keyword) ||
                    profile.configType.name.matchesPattern(regex, keyword)
        }
    }

    private fun updateGroupUi(groupId: String, servers: List<ServersCache>) {
        val filteredServers = applyKeywordFilter(servers)
        mutableServerGroupState(groupId).value = ServerGroupUiState(
            servers = filteredServers,
            rows = buildServerRows(groupId, filteredServers)
        )
    }

    private fun buildServerRows(groupId: String, servers: List<ServersCache>): List<ServerRowUiModel> {
        val subscriptionRemarks = if (groupId.isEmpty()) {
            servers.asSequence()
                .map { it.profile.subscriptionId }
                .filter { it.isNotEmpty() }
                .distinct()
                .associateWith { subscriptionId ->
                    dataSource.getSubscriptionItem(subscriptionId)?.remarks.orEmpty()
                }
        } else {
            emptyMap()
        }
        return servers.map { server ->
            buildServerRowUiModel(
                server = server,
                subscriptionRemarks = subscriptionRemarks[server.profile.subscriptionId].orEmpty(),
                serviceCheck = serviceCheckReports[server.guid]?.toRowUiModel()
                    ?: ServerServiceCheckUiModel(),
            )
        }
    }

    fun getSubscriptions(): List<SubscriptionCache> = dataSource.getSubscriptions()

    private fun resolveSelectedGroup(groups: List<GroupMapItem>): String {
        val current = uiState.value.selectedGroupId
        val resolved = when {
            groups.isEmpty() -> ""
            groups.any { it.id == current } -> current
            else -> groups.first().id
        }
        if (resolved != current) {
            dataSource.setSelectedSubscriptionId(resolved)
        }
        return resolved
    }

    private fun radialPreloadOrder(groups: List<GroupMapItem>, selectedIndex: Int): List<String> {
        if (groups.isEmpty()) return emptyList()
        val result = ArrayList<String>((groups.size - 1).coerceAtLeast(0))
        for (distance in 1 until groups.size) {
            val right = selectedIndex + distance
            val left = selectedIndex - distance
            if (right in groups.indices) result += groups[right].id
            if (left in groups.indices) result += groups[left].id
        }
        return result
    }

    fun setupGroupTab(forceRefresh: Boolean = false): Job {
        setupGroupJob?.cancel()
        preloadJob?.cancel()
        selectedGroupLoadJob?.cancel()

        return viewModelScope.launch(ioDispatcher) {
            try {
                if (forceRefresh) {
                    cacheMutex.withLock { groupDataCache.clear() }
                }
                val groups = dataSource.getSubscriptions().map {
                    GroupMapItem(
                        id = it.guid,
                        remarks = it.subscription.remarks,
                        usageInfo = it.subscription.usageInfo?.let { info ->
                            SubscriptionInfoParser.withWebPageFallback(info, it.subscription.url)
                        },
                    )
                }
                val selectedGroup = resolveSelectedGroup(groups)
                val validIds = groups.mapTo(HashSet()) { it.id }
                groupUiFlows.keys.removeAll { it !in validIds }
                groupServerFlows.keys.removeAll { it !in validIds }
                groupLoadMutexes.keys.removeAll { it !in validIds }

                _uiState.update {
                    it.copy(
                        groups = groups,
                        selectedGroupId = selectedGroup,
                        selectedGuid = dataSource.getSelectServer(),
                    )
                }
                groups.forEach { mutableServerGroupState(it.id) }

                if (groups.isEmpty()) {
                    cacheMutex.withLock { groupDataCache.clear() }
                    return@launch
                }

                val selectedServers = loadGroup(selectedGroup, forceRefresh)
                loadServiceCheckReports()
                updateGroupUi(selectedGroup, selectedServers)

                if (!initialPageReady.isCompleted) {
                    initialPageReady.complete(Unit)
                }

                val selectedIndex =
                    groups.indexOfFirst { it.id == selectedGroup }.coerceAtLeast(0)
                val preloadOrder = radialPreloadOrder(groups, selectedIndex)
                preloadJob = viewModelScope.launch(preloadDispatcher) {
                    preloadOrder.forEach { groupId ->
                        ensureActive()
                        delay(32)
                        val servers = loadGroup(groupId, forceRefresh)
                        updateGroupUi(groupId, servers)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to set up group tabs", error)
            } finally {
                if (!initialPageReady.isCompleted) {
                    initialPageReady.complete(Unit)
                }
            }
        }.also { setupGroupJob = it }
    }

    // ---------- Business actions (coroutine-based) ----------
    private fun importBatchConfig(configText: String) {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val (count, countSub) = dataSource.importBatchConfig(
                        configText, uiState.value.selectedGroupId, true
                    )
                    when {
                        count > 0 -> {
                            toast(dataSource.getString(R.string.title_import_config_count, count))
                            setupGroupTab(forceRefresh = true)
                        }

                        countSub > 0 -> setupGroupTab(forceRefresh = true)
                        else -> toastError(R.string.toast_failure)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to import batch config", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun importConfigViaSub(subId: String) {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val result = if (subId.isEmpty()) {
                        dataSource.updateConfigViaSubAll()
                    } else {
                        val item = dataSource.getSubscriptionItem(subId) ?: return@withContext
                        dataSource.updateConfigViaSub(SubscriptionCache(subId, item))
                    }
                    when {
                        result.successCount + result.failureCount + result.skipCount == 0 ->
                            toast(R.string.title_update_subscription_no_subscription)

                        result.successCount > 0 && result.failureCount + result.skipCount == 0 ->
                            toast(
                                getQuantityString(
                                    R.plurals.title_update_config_count,
                                    result.configCount,
                                    result.configCount,
                                )
                            )

                        else ->
                            toast(dataSource.getString(R.string.title_update_subscription_result, result.configCount, result.successCount, result.failureCount, result.skipCount))
                    }
                    if (result.configCount > 0) {
                        setupGroupTab(forceRefresh = true)
                        refreshSelectedGuid()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Subscription update failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun exportAllAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val groupId = uiState.value.selectedGroupId
                    val list = if (groupId.isEmpty() && keywordFilter.isEmpty()) {
                        dataSource.getServerGuidList("")
                    } else {
                        currentServers().map { it.guid }
                    }
                    val ret = dataSource.shareNonCustomConfigsToClipboard(list)
                    if (ret > 0) {
                        toast(dataSource.getString(R.string.title_export_config_count, ret))
                    } else {
                        toastError(R.string.toast_failure)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Export failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeAllServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val count =
                        if (uiState.value.selectedGroupId.isEmpty() && keywordFilter.isEmpty()) {
                            dataSource.removeAllServer()
                        } else {
                            val guids = currentServers().map { it.guid }
                            guids.forEach { dataSource.removeServer(it) }
                            guids.size
                        }
                    viewModelScope.launch(ioDispatcher) {
                        cacheMutex.withLock { groupDataCache.clear() }
                    }
                    setupGroupTab(forceRefresh = true)
                    toast(dataSource.getString(R.string.title_del_config_count, count))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete all failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeDuplicateServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val seen = HashSet<ProfileItem>()
                    val duplicates = ArrayList<String>()
                    currentServers().forEach { server ->
                        val profile = server.profile
                        if (!profile.configType.isComplexType()) {
                            val identity = profile.duplicateIdentity()
                            if (!seen.add(identity)) duplicates += server.guid
                        }
                    }
                    duplicates.forEach { dataSource.removeServer(it) }
                    setupGroupTab(forceRefresh = true)
                    toast(dataSource.getString(R.string.title_del_duplicate_config_count, duplicates.size))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete duplicate failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeInvalidServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val count = removeInvalidServerInternal()
                    viewModelScope.launch(ioDispatcher) {
                        cacheMutex.withLock { groupDataCache.clear() }
                        setupGroupTab(forceRefresh = true)
                    }
                    toast(dataSource.getString(R.string.title_del_config_count, count))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete invalid failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeInvalidServerInternal(): Int {
        val visibleServersOnly =
            uiState.value.selectedGroupId.isNotEmpty() || keywordFilter.isNotBlank()
        return if (visibleServersOnly) {
            currentServers().sumOf { server ->
                dataSource.removeInvalidServerByGuid(server.guid)
            }
        } else {
            dataSource.removeInvalidServersInGroup("")
        }
    }

    private fun sortByTestResultsAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    sortByTestResultsInternal()
                    cacheMutex.withLock { groupDataCache.clear() }
                    setupGroupTab(forceRefresh = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Sort by test results failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun sortByTestResultsInternal(groupId: String = uiState.value.selectedGroupId) {
        val subs = if (groupId.isEmpty()) {
            dataSource.getSubsList()
        } else {
            listOf(groupId)
        }
        subs.forEach { dataSource.sortByTestResultsForSub(it) }
    }

    /**
     * Sorts the list of the current group by the YouTube result.
     *
     * The order is written the same way as the order of the test results, only the
     * ranking is different.
     */
    private fun sortByYoutubeResultsAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    sortByYoutubeResultsInternal()
                    cacheMutex.withLock { groupDataCache.clear() }
                    setupGroupTab(forceRefresh = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Sort by YouTube results failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    /**
     * Sorts the list of a group by the YouTube result.
     *
     * The group with an empty id shows the profiles of every subscription, every
     * subscription is sorted on its own then.
     *
     * @param groupId The id of the group.
     */
    private fun sortByYoutubeResultsInternal(groupId: String = uiState.value.selectedGroupId) {
        val subs = if (groupId.isEmpty()) {
            dataSource.getSubsList()
        } else {
            listOf(groupId)
        }
        subs.forEach { dataSource.sortByYoutubeResultsForSub(it) }
    }

    /**
     * Sorts the list of the checked group by the YouTube result, when the run is over.
     *
     * The sort only runs when the setting "Auto sort after testing" is on. A run that
     * was stopped before every config was checked does not sort the list.
     *
     * @param groupId The id of the group of the run.
     */
    private fun sortByYoutubeResultsAfterRun(groupId: String) {
        viewModelScope.launch(ioDispatcher) {
            try {
                if (!dataSource.isAutoSortAfterTestEnabled()) return@launch
                sortByYoutubeResultsInternal(groupId)
                applyServerOrder(groupId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Sort by YouTube results after the run failed", e)
            }
        }
    }

    /**
     * Removes every service check report.
     *
     * A running service check is stopped first, so it cannot write a report after the
     * store was cleared. The real delay results are not touched, they belong to the
     * real delay test.
     */
    private fun clearServiceCheckResults() {
        launchLoading {
            try {
                // The workers are stopped and awaited first, so no report can be
                // written after the store was emptied.
                ServiceCheckManager.cancelAndJoin()
                withContext(ioDispatcher) {
                    dataSource.clearServiceCheckResults()
                    refreshServiceCheckRows()
                }
                toastSuccess(R.string.toast_service_check_results_cleared)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Clear service check results failed", e)
                toastError(R.string.toast_failure)
            }
        }
    }

    // ---------- Service check ----------
    /**
     * Starts the service check of every profile of the current group that has a real
     * delay result, or stops the running check.
     *
     * The run is started with the same API that the "YT" button of the next stage
     * uses, so both start the same run.
     */
    private fun toggleYouTubeCheck() {
        if (ServiceCheckManager.state.value.isRunning) {
            ServiceCheckManager.cancel()
            toast(R.string.toast_service_check_cancelled)
            return
        }
        viewModelScope.launch {
            val guids = withContext(ioDispatcher) { testedConfigGuids() }
            if (guids.isEmpty()) {
                toastError(
                    dataSource.getString(
                        R.string.toast_service_check_no_tested_configs,
                        dataSource.getString(R.string.title_real_ping_all_server)
                    )
                )
                return@launch
            }
            ServiceCheckManager.start(guids)
            toast(dataSource.getString(R.string.toast_service_check_started, guids.size))
        }
    }

    /**
     * The GUIDs of the profiles of the current group that have a real delay result.
     *
     * An empty group id is the group that shows the servers of every subscription.
     */
    private fun testedConfigGuids(): List<String> {
        val groupId = uiState.value.selectedGroupId
        return dataSource.getServerGuidList(groupId).filter { guid ->
            (dataSource.decodeAffiliationInfo(guid)?.testDelayMillis ?: 0L) > 0L
        }
    }

    /**
     * Watches the state of the service check manager.
     *
     * The manager lives in the application, so a run also survives the recreation of the
     * activity. When the run is over, the rows are rebuilt, so the chips of the list
     * show the new results.
     */
    private fun observeServiceCheckState() {
        viewModelScope.launch {
            ServiceCheckManager.state.collect { state ->
                val wasRunning = ytRunActive
                ytRunActive = state.isRunning
                updateYtProgressText(state)
                if (state.isRunning) {
                    serviceCheckRunStarted = true
                    if (!wasRunning) {
                        // The group of the run is remembered, the final sort uses it.
                        if (ytRunGroupId == null) {
                            ytRunGroupId = uiState.value.selectedGroupId
                        }
                        // The list is sorted by the YouTube result while a run is
                        // active. The watcher of the delay test is already running when
                        // both tests run at the same time.
                        if (!isBulkTestRunning()) {
                            startLiveSortWatcher(ytRunGroupId ?: uiState.value.selectedGroupId)
                        }
                    }
                    return@collect
                }
                if (!serviceCheckRunStarted) return@collect
                serviceCheckRunStarted = false
                // The rows show the reports of the run, also when the run was stopped
                // before every config was checked.
                withContext(ioDispatcher) {
                    refreshServiceCheckRows()
                }
                // A run that was stopped before every config was checked is not a
                // finished run, it must not report a result.
                if (state.total <= 0 || state.done < state.total) {
                    ytRunGroupId = null
                    return@collect
                }
                toast(
                    dataSource.getString(
                        R.string.toast_service_check_done,
                        state.goodCount,
                        state.total
                    )
                )
                // The list is sorted one last time by the YouTube result.
                sortByYoutubeResultsAfterRun(ytRunGroupId ?: uiState.value.selectedGroupId)
                ytRunGroupId = null
            }
        }
    }

    /**
     * Shows the progress of the service check in the status line of the screen.
     *
     * @param state The state of the service check manager.
     */
    private fun updateYtProgressText(state: ServiceCheckRunState) {
        val text = if (state.isRunning && state.total > 0) {
            dataSource.getString(
                R.string.service_check_progress_youtube,
                state.done,
                state.total,
                state.goodCount,
            )
        } else {
            null
        }
        if (text == lastYtProgress) return
        lastYtProgress = text
        if (text == null) {
            // The run is over, the status line shows the delay test again.
            if (lastDelayProgress == null) {
                resetTestStatus()
            } else {
                publishProgressText()
            }
            if (!isBulkTestRunning()) {
                stopLiveSortWatcher()
            }
            return
        }
        publishProgressText()
    }

    /**
     * Collects the finished service checks.
     *
     * The report comes with the event, so the store is not read again for every config.
     * The rows are updated in batches, the same way the delay results are.
     */
    private fun observeServiceCheckEvents() {
        viewModelScope.launch {
            ServiceCheckManager.events.collect { event ->
                serviceCheckReports[event.guid] = event.report
                pendingServiceCheckReports[event.guid] = event.report
                liveSortHasNewYtResult = true
                scheduleServiceCheckFlush()
            }
        }
    }

    /**
     * Starts the flush of the finished service checks, if it is not running already.
     */
    private fun scheduleServiceCheckFlush() {
        if (serviceCheckFlushJob?.isActive == true) return

        serviceCheckFlushJob = viewModelScope.launch {
            while (pendingServiceCheckReports.isNotEmpty()) {
                delay(SERVICE_CHECK_FLUSH_INTERVAL_MS)
                flushPendingServiceCheckReports()
            }
        }
    }

    /**
     * Puts the finished service checks into the rows.
     *
     * Only the rows of the changed configs get a new instance, the other rows keep
     * theirs and are not recomposed.
     */
    private fun flushPendingServiceCheckReports() {
        val drained = pendingServiceCheckReports.toMap()
        pendingServiceCheckReports.clear()
        if (drained.isEmpty()) return

        groupUiFlows.keys.toList().forEach { groupId ->
            mutableServerGroupState(groupId).update { current ->
                current.copy(rows = applyServiceCheckReportsToRows(current.rows, drained))
            }
        }
    }

    /**
     * Reads the service check reports of every checked profile.
     *
     * The reports live in their own store, they never touch the real delay results.
     */
    private suspend fun loadServiceCheckReports() {
        val guids = dataSource.getServiceCheckReportGuids()
        val reports = HashMap<String, ServiceCheckReport>(guids.size)
        guids.forEach { guid ->
            dataSource.decodeServiceCheckReport(guid)?.let { reports[guid] = it }
        }
        serviceCheckReports.clear()
        serviceCheckReports.putAll(reports)
    }

    /**
     * Reads the service check reports again and rebuilds the rows of the current group.
     */
    private suspend fun refreshServiceCheckRows() {
        loadServiceCheckReports()
        val groupId = uiState.value.selectedGroupId
        val servers = cacheMutex.withLock { groupDataCache[groupId] } ?: return
        updateGroupUi(groupId, servers)
    }

    /**
     * Shows the report of one profile in the details dialog.
     */
    private fun showServiceCheckDetails(guid: String) {
        viewModelScope.launch {
            val details = withContext(ioDispatcher) {
                val report = dataSource.decodeServiceCheckReport(guid) ?: return@withContext null
                val remarks = dataSource.decodeServerConfig(guid)?.remarks.orEmpty()
                report.toDetailsUiModel(remarks)
            }
            if (details == null) {
                toastError(R.string.toast_service_check_no_report)
                return@launch
            }
            _uiState.update { it.copy(serviceCheckDetails = details) }
        }
    }

    /**
     * Closes the details dialog.
     */
    private fun dismissServiceCheckDetails() {
        _uiState.update { it.copy(serviceCheckDetails = null) }
    }

    /**
     * Copies the whole captured body of one check to the clipboard.
     */
    private fun copyServiceCheckBody(checkId: String) {
        val details = _uiState.value.serviceCheckDetails ?: return
        val body = details.bodyOf(checkId)
        if (body.isNullOrEmpty()) {
            toastError(R.string.toast_service_check_no_body)
            return
        }
        Utils.setClipboard(app, body)
        toastSuccess(R.string.toast_service_check_body_copied)
    }

    fun subscriptionIdChanged(id: String) {
        if (_uiState.value.groups.none { it.id == id }) return
        mutableServerGroupState(id)
        if (uiState.value.selectedGroupId != id) {
            dataSource.setSelectedSubscriptionId(id)
            _uiState.update { it.copy(selectedGroupId = id) }
        }
        selectedGroupLoadJob?.cancel()
        selectedGroupLoadJob = viewModelScope.launch(ioDispatcher) {
            try {
                updateGroupUi(id, loadGroup(id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to load selected group: $id", error)
            }
        }
    }

    fun reloadServerList() {
        val groupId = uiState.value.selectedGroupId
        selectedGroupLoadJob?.cancel()
        selectedGroupLoadJob = viewModelScope.launch(ioDispatcher) {
            updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
        }
    }

    fun reloadAllGroups(groupIds: List<String>) {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch(preloadDispatcher) {
            val selected = uiState.value.selectedGroupId
            val order = buildList {
                if (selected in groupIds) add(selected)
                addAll(groupIds.filter { it != selected })
            }
            order.forEachIndexed { index, groupId ->
                ensureActive()
                if (index > 0) delay(32)
                updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
            }
        }
    }

    fun filterConfig(keyword: String) {
        if (keyword == keywordFilter) return
        keywordFilter = keyword
        filterJob?.cancel()
        filterJob = viewModelScope.launch(defaultDispatcher) {
            delay(300)
            val snapshot = cacheMutex.withLock { groupDataCache.toMap() }
            ensureActive()
            snapshot.forEach { (groupId, servers) ->
                ensureActive()
                updateGroupUi(groupId, servers)
            }
        }
    }

    fun updateSelectedGuid(guid: String) {
        dataSource.setSelectServer(guid)
        _uiState.update { it.copy(selectedGuid = guid) }
    }

    fun refreshSelectedGuid() {
        _uiState.update { it.copy(selectedGuid = dataSource.getSelectServer()) }
    }

    fun removeServerAndRefresh(guid: String) {
        if (guid == uiState.value.selectedGuid) {
            toast(R.string.toast_action_not_allowed)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            dataSource.removeServer(guid)
            cacheMutex.withLock { groupDataCache.clear() }
            setupGroupTab(forceRefresh = true).join()
        }
    }

    fun moveServer(groupId: String, fromPosition: Int, toPosition: Int) {
        val groupState = mutableServerGroupState(groupId).value
        val servers = groupState.servers.toMutableList()
        if (!servers.moveItem(fromPosition, toPosition)) return
        val rows = groupState.rows.toMutableList()
        rows.moveItem(fromPosition, toPosition)
        val guids = servers.map { it.guid }
        mutableServerGroupState(groupId).value = ServerGroupUiState(servers, rows)
        // A drag emits several moves; serialize writes so an older order cannot overwrite a newer one.
        val previousPersistenceJob = serverOrderPersistenceJobs[groupId]
        serverOrderPersistenceJobs[groupId] = viewModelScope.launch(ioDispatcher) {
            previousPersistenceJob?.join()
            dataSource.encodeServerList(guids, groupId)
            cacheMutex.withLock { groupDataCache[groupId] = servers }
        }
    }

    // ---------- Live sorting while a real-delay test runs ----------
    /**
     * Starts the watcher that re-sorts the list while a real-delay test runs.
     *
     * The watcher only evaluates the conditions on the main thread. The sort itself runs
     * on the IO dispatcher, because it reads and writes MMKV.
     */
    private fun startLiveSortWatcher(groupId: String) {
        stopLiveSortWatcher()
        liveSortHasNewDelayResult = false
        liveSortHasNewYtResult = false
        liveSortLastAtMs = 0L
        liveSortJob = viewModelScope.launch {
            // The setting lives in MMKV, so it is read off the main thread.
            val enabled = withContext(ioDispatcher) { dataSource.isSortWhileTestingEnabled() }
            if (!enabled) return@launch
            while (isActive) {
                delay(LIVE_SORT_TICK_MS)
                if (!liveSortDue()) continue
                withContext(ioDispatcher) {
                    runLiveSort(groupId)
                }
            }
        }
    }

    private fun stopLiveSortWatcher() {
        liveSortJob?.cancel()
        liveSortJob = null
        liveSortHasNewDelayResult = false
        liveSortHasNewYtResult = false
    }

    /**
     * Decides whether the list may be re-sorted right now.
     *
     * A sort needs a new valid result, must not run while the user scrolls the list and
     * must not run more often than every [LIVE_SORT_MIN_INTERVAL_MS]. While a YouTube
     * check is running, a new report of the check also starts a sort.
     */
    private fun liveSortDue(): Boolean {
        val hasNewResult =
            liveSortHasNewDelayResult || (ytRunActive && liveSortHasNewYtResult)
        if (!hasNewResult) return false
        if (listScrolling) return false
        val now = SystemClock.elapsedRealtime()
        if (now - liveSortLastAtMs < LIVE_SORT_MIN_INTERVAL_MS) return false
        liveSortLastAtMs = now
        liveSortHasNewDelayResult = false
        liveSortHasNewYtResult = false
        return true
    }

    private suspend fun runLiveSort(groupId: String) {
        try {
            // While a YouTube check is running, the list is sorted by the YouTube
            // result of the checked group, the delay test keeps its own ranking.
            if (ytRunActive) {
                val ytGroupId = ytRunGroupId ?: groupId
                sortByYoutubeResultsInternal(ytGroupId)
                applyServerOrder(ytGroupId)
            } else {
                sortByTestResultsInternal(groupId)
                applyServerOrder(groupId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Live sort failed", e)
        }
    }

    /**
     * Applies the new order of the group to the list.
     *
     * The rows keep their instances, only their position changes, so a re-sort does not
     * rebuild the whole row model.
     */
    private suspend fun applyServerOrder(groupId: String) {
        val order = dataSource.getServerGuidList(groupId)
        val rank = HashMap<String, Int>(order.size)
        order.forEachIndexed { index, guid -> rank[guid] = index }
        cacheMutex.withLock {
            val cached = groupDataCache[groupId]
            if (cached != null) {
                groupDataCache[groupId] = cached.sortedBy { rank[it.guid] ?: Int.MAX_VALUE }
            }
        }
        mutableServerGroupState(groupId).update { current ->
            current.copy(
                servers = current.servers.sortedBy { rank[it.guid] ?: Int.MAX_VALUE },
                rows = current.rows.sortedBy { rank[it.guid] ?: Int.MAX_VALUE },
            )
        }
    }

    /**
     * Remembers whether the user is touching or scrolling the server list.
     */
    private fun setListScrolling(active: Boolean) {
        listScrolling = active
    }

    /**
     * Returns the profile of a server of a group, or null when it is not in the group.
     */
    internal fun profileInGroup(groupId: String, guid: String): ProfileItem? =
        mutableServerGroupState(groupId).value.servers.firstOrNull { it.guid == guid }?.profile

    // ---------- Testing ----------
    fun cancelAllPing() {
        bulkTestJob?.cancel()
        bulkTestJob = null
        testRequests.cancelBulk()
        testRequests.invalidateCurrent()
        cancelPendingTestResults()
        if (!ytRunActive) {
            stopLiveSortWatcher()
        }
        resetTestStatus()
        // A service check can still be running, its progress stays in the status line.
        publishProgressText()
        dataSource.cancelAllPing()
    }

    /**
     * Returns whether a real-delay test of the whole list is running.
     *
     * The watcher of the live sort is shared by the delay test and the service check,
     * so it must only be stopped when no test is running any more.
     */
    private fun isBulkTestRunning(): Boolean = testRequests.bulk != null

    private fun resetTestStatus() {
        _uiState.update {
            it.copy(
                isTesting = testRequests.isTesting,
                status = if (testRequests.isTesting) MainStatus.Testing
                else if (it.isRunning) MainStatus.Connected else MainStatus.Disconnected
            )
        }
    }

    /**
     * Starts a real-delay test of the current group.
     *
     * @param onlyTcp Whether only the TCP delay is measured.
     * @param concurrency The number of tests that run at the same time. Zero means the
     * user setting is used, a positive number is an override for this run only. The
     * user setting itself is never changed.
     */
    fun testAllRealPing(onlyTcp: Boolean = false, concurrency: Int = 0) {
        cancelAllPing()
        val groupId = uiState.value.selectedGroupId
        val servers = currentServers()
        if (servers.isEmpty()) {
            return
        }
        val serverGuids = servers.map { it.guid }
        mutableServerGroupState(groupId).update { current ->
            current.copy(
                servers = current.servers.map { server ->
                    if (server.testDelayMillis == 0L) server
                    else server.copy(testDelayMillis = 0L)
                },
                rows = current.rows.map { row ->
                    if (row.testDelayMillis == 0L) row
                    else row.copy(testDelayMillis = 0L)
                }
            )
        }
        val request = testRequests.beginBulk(groupId)
        val message = TestServiceMessage(
            key = AppConfig.MSG_MEASURE_CONFIG_START,
            subscriptionId = groupId,
            serverGuids = if (keywordFilter.isNotEmpty()) serverGuids else emptyList(),
            onlyTcp = onlyTcp,
            concurrency = concurrency
        )
        startLiveSortWatcher(groupId)
        _uiState.update {
            it.copy(
                isTesting = true,
                status = MainStatus.Testing
            )
        }
        // A service check can still be running, its progress stays in the status line.
        publishProgressText()
        bulkTestJob = viewModelScope.launch {
            withContext(ioDispatcher) {
                dataSource.clearAllTestDelayResults(serverGuids)
                val resetGuids = serverGuids.toHashSet()
                cacheMutex.withLock {
                    groupDataCache[groupId]?.let { cached ->
                        groupDataCache[groupId] = cached.map { server ->
                            if (server.guid !in resetGuids || server.testDelayMillis == 0L) server
                            else server.copy(testDelayMillis = 0L)
                        }
                    }
                }
            }
            dataSource.sendMsg2TestService(message, request.id)
        }
    }

    private fun cancelPendingTestResults() {
        testResultFlushJob?.cancel()
        testResultFlushJob = null
        pendingTestResults.clear()
        pendingTestProgress = null
        lastDelayProgress = null
    }

    fun testCurrentServerRealPing() {
        if (!uiState.value.isRunning) return
        val requestId = testRequests.beginCurrent()
        _uiState.update { it.copy(isTesting = true, status = MainStatus.Testing) }
        dataSource.testCurrentServerRealPing(requestId)
    }

    private fun onTestsFinished(requestId: String) {
        if (testRequests.completeBulk(requestId) == null) return
        if (ytRunActive) {
            // A service check is still running, its watcher sorts the list by the
            // YouTube result.
            startLiveSortWatcher(ytRunGroupId ?: uiState.value.selectedGroupId)
        } else {
            stopLiveSortWatcher()
        }
        resetTestStatus()
        // A service check can still be running, its progress stays in the status line.
        publishProgressText()
        viewModelScope.launch(ioDispatcher) {
            cacheMutex.withLock { groupDataCache.clear() }
            reloadAllGroups(_uiState.value.groups.map { it.id })
        }
    }

    fun triggerLocateSelectedServer() {
        val selected = dataSource.getSelectServer() ?: return
        val profile = dataSource.decodeServerConfig(selected) ?: return
        val groupId = profile.subscriptionId
        if (_uiState.value.groups.none { it.id == groupId }) return
        viewModelScope.launch(ioDispatcher) {
            updateGroupUi(groupId, loadGroup(groupId))
            if (_uiState.value.selectedGroupId != groupId) {
                dataSource.setSelectedSubscriptionId(groupId)
            }
            val target = LocateTarget(groupId, selected)
            _uiState.update {
                it.copy(selectedGroupId = groupId, locateTarget = target)
            }
        }
    }

    private fun consumeLocateTarget() {
        _uiState.update { it.copy(locateTarget = null) }
    }

    // ---------- Running state ----------
    private fun updateRunningState(running: Boolean, clearTestingText: Boolean = true) {
        if (!running || clearTestingText) testRequests.invalidateCurrent()
        _uiState.update { state ->
            state.copy(
                isRunning = running,
                isTesting = testRequests.isTesting,
                status = if (!clearTestingText && state.isRunning == running) state.status
                else if (running) MainStatus.Connected else MainStatus.Disconnected
            )
        }
    }

    override fun onCleared() {
        setupGroupJob?.cancel()
        preloadJob?.cancel()
        selectedGroupLoadJob?.cancel()
        reloadJob?.cancel()
        filterJob?.cancel()
        cancelAllPing()
        dataSource.close()
        super.onCleared()
    }

    // ---------- Factory ----------
    class Factory(private val application: Application, private val dataSource: MainDataSource) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                return MainViewModel(application, dataSource) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    private companion object {
        const val TEST_RESULT_FLUSH_INTERVAL_MS = 500L
        const val SERVICE_CHECK_FLUSH_INTERVAL_MS = 400L
        const val LIVE_SORT_TICK_MS = 500L
        const val LIVE_SORT_MIN_INTERVAL_MS = 1_500L
    }
}
