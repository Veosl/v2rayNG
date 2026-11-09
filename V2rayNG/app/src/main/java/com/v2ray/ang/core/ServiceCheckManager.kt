package com.v2ray.ang.core

import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ServiceCheckReport
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Number of configs that are checked at the same time.
 *
 * The real-ping worker uses the user setting for this, but a service check starts one
 * native core per config, so the amount of parallel cores is kept low and constant.
 */
const val SERVICE_CHECK_CONCURRENCY = 3

/**
 * State of the running service check.
 *
 * @param isRunning Whether a run is active.
 * @param done The number of configs that were checked.
 * @param total The number of configs of the run.
 * @param goodCount The number of configs that are usable for YouTube.
 */
data class ServiceCheckRunState(
    val isRunning: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val goodCount: Int = 0,
)

/**
 * Runs the service checks of the configs of the whole app.
 *
 * The manager is an application scoped object with its own scope, so a run survives the
 * recreation of the main activity. The results are written to the service check store
 * of [MmkvManager], they do not live in the memory of a screen.
 */
object ServiceCheckManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(ServiceCheckRunState())

    /** The state of the running service check. */
    val state: StateFlow<ServiceCheckRunState> = _state.asStateFlow()

    private val lock = Any()
    private var queue: Channel<String>? = null
    private var workers: List<Job> = emptyList()
    private var pending = 0

    /**
     * Starts a run with the given configs.
     *
     * A run that is already active is stopped first, so two runs never check the same
     * config at the same time. The configs are queued, the workers check them with
     * [SERVICE_CHECK_CONCURRENCY] checks at the same time.
     */
    fun start(guids: List<String>) {
        val unique = guids.asSequence().filter { it.isNotBlank() }.distinct().toList()
        // The previous run is stopped before the new workers are started, so two runs
        // never check the same config at the same time.
        synchronized(lock) {
            stopWorkers()
            pending = 0
        }
        if (unique.isEmpty()) {
            synchronized(lock) {
                _state.value = ServiceCheckRunState()
            }
            return
        }
        val newQueue = Channel<String>(Channel.UNLIMITED)
        // The workers are started by hand, so the run state is complete before the
        // first config is checked.
        val jobs = List(SERVICE_CHECK_CONCURRENCY) {
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    for (guid in newQueue) {
                        checkOne(guid)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Service check worker failed", e)
                }
            }
        }
        synchronized(lock) {
            queue = newQueue
            workers = jobs
            pending = unique.size
            _state.value = ServiceCheckRunState(isRunning = true, total = unique.size)
        }
        jobs.forEach { it.start() }
        scope.launch {
            try {
                unique.forEach { newQueue.send(it) }
            } catch (e: ClosedSendChannelException) {
                LogUtil.i(AppConfig.TAG, "Service check run was cancelled before it started")
            }
        }
    }

    /**
     * Adds one config to the running check.
     *
     * A check that is not running yet is started with the config.
     */
    fun enqueue(guid: String) {
        if (guid.isBlank()) return
        val queued = synchronized(lock) {
            val current = queue
            if (current == null) {
                false
            } else {
                pending++
                val sent = current.trySend(guid)
                if (sent.isSuccess) {
                    _state.update { it.copy(isRunning = true, total = it.total + 1) }
                    true
                } else {
                    pending--
                    false
                }
            }
        }
        if (!queued) start(listOf(guid))
    }

    /**
     * Stops the running check.
     *
     * The temporary cores of the running checks are stopped by the runner.
     */
    fun cancel() {
        synchronized(lock) {
            stopWorkers()
            pending = 0
            _state.update { it.copy(isRunning = false) }
        }
    }

    /**
     * Stops the running check and waits until every worker is gone.
     *
     * The caller can empty the service check store after this call, no worker can
     * write a report any more.
     */
    suspend fun cancelAndJoin() {
        val jobs = synchronized(lock) {
            val stopped = stopWorkers()
            pending = 0
            _state.update { it.copy(isRunning = false) }
            stopped
        }
        jobs.joinAll()
    }

    /**
     * Stops the workers and closes the queue, the caller holds the lock.
     *
     * @return The jobs of the stopped workers.
     */
    private fun stopWorkers(): List<Job> {
        val stopped = workers
        stopped.forEach { it.cancel() }
        workers = emptyList()
        queue?.close()
        queue = null
        return stopped
    }

    /**
     * Ends the run when every queued config was checked, the caller holds the lock.
     *
     * The queue is closed so the workers leave their loop, they are not cancelled
     * because they have no work left.
     */
    private fun finishRun() {
        queue?.close()
        queue = null
    }

    /**
     * Checks one config and writes its report to the service check store.
     */
    private suspend fun checkOne(guid: String) {
        val report = try {
            ServiceCheckRunner.check(AngApplication.application, guid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Service check failed for $guid", e)
            ServiceCheckReport(
                guid = guid,
                timestamp = System.currentTimeMillis(),
                error = e.message ?: e.javaClass.simpleName,
            )
        }
        withContext(Dispatchers.IO) {
            MmkvManager.encodeServiceCheckReport(report)
        }
        synchronized(lock) {
            pending = (pending - 1).coerceAtLeast(0)
            _state.update { current ->
                current.copy(
                    done = current.done + 1,
                    goodCount = current.goodCount + if (ServiceCheckRunner.isYtOk(report)) 1 else 0,
                )
            }
            if (pending == 0) {
                // The run is over, the workers are released until the next run starts.
                finishRun()
                _state.update { it.copy(isRunning = false) }
            }
        }
    }
}
