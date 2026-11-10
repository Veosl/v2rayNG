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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.PriorityQueue

/**
 * One config that waits for its check.
 *
 * @param guid The GUID of the profile.
 * @param delayMillis The real delay of the profile. The config with the lowest delay is
 * checked first.
 * @param sequence The order in which the config was added. It keeps the order of the
 * configs with the same delay stable.
 */
private data class PendingCheck(
    val guid: String,
    val delayMillis: Long,
    val sequence: Long,
)

/**
 * State of the running service check.
 *
 * @param isRunning Whether a run is active.
 * @param done The number of configs that were checked.
 * @param total The number of configs of the run. It grows while a pipeline run adds
 * more configs.
 * @param goodCount The number of configs that are usable for YouTube.
 * @param runId The id of the current run, it changes with every run.
 * @param inputClosed Whether the run takes no more configs.
 */
data class ServiceCheckRunState(
    val isRunning: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val goodCount: Int = 0,
    val runId: Long = 0L,
    val inputClosed: Boolean = false,
)

/**
 * One finished service check.
 *
 * The event carries the report of the config, so the screen does not have to read the
 * store again to show the result of the config.
 *
 * @param runId The id of the run the check belongs to.
 * @param guid The GUID of the profile.
 * @param report The report of the profile.
 * @param isYtOk Whether the profile is usable for YouTube.
 */
data class ServiceCheckEvent(
    val runId: Long,
    val guid: String,
    val report: ServiceCheckReport,
    val isYtOk: Boolean,
)

/**
 * Runs the service checks of the configs.
 *
 * The manager lives in the application, so a run survives the recreation of the
 * activity. There are two kinds of run:
 *
 * * a **standalone run** ([start]) checks a fixed list of configs, the input is closed
 *   at once and the run ends when every config was checked.
 * * a **pipeline run** ([beginPipeline]) takes its configs while it runs
 *   ([enqueue]), the config with the lowest real delay is checked first and the run
 *   ends when the input was closed ([endInput]) and every config was checked.
 *
 * The reports live in their own store ([MmkvManager]), they never touch the real delay
 * results.
 */
object ServiceCheckManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(ServiceCheckRunState())

    /** The state of the running service check. */
    val state: StateFlow<ServiceCheckRunState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ServiceCheckEvent>(
        replay = 0,
        extraBufferCapacity = ServiceCheckTuning.SERVICE_CHECK_EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * One event per finished config.
     *
     * The buffer is big enough for a whole burst of results, so a worker never waits
     * for the screen. The events of a run are not replayed, a screen that opens in the
     * middle of a run reads the reports of the store instead.
     */
    val events: SharedFlow<ServiceCheckEvent> = _events.asSharedFlow()

    private val lock = Any()

    /** The configs that wait for their check, the lowest delay comes first. */
    private val pendingChecks =
        PriorityQueue<PendingCheck>(compareBy<PendingCheck> { it.delayMillis }.thenBy { it.sequence })

    /** The GUIDs of the current run, a config is only checked once per run. */
    private val seenGuids = HashSet<String>()

    /** Wakes a worker that waits for work. */
    private val workSignal = Channel<Unit>(Channel.UNLIMITED)

    private var workers: List<Job> = emptyList()

    /** The number of workers that check a config at the moment. */
    private var activeWorkers = 0

    /** Whether the run takes no more configs. */
    private var inputClosed = false

    /** Whether the run is over, the workers leave their loop. */
    private var runOver = false

    private var sequenceCounter = 0L
    private var runCounter = 0L
    private var currentRunId = 0L

    /**
     * A new id for a run.
     *
     * Every run has its own id, so the events of an old run can be told apart from the
     * events of the current one.
     */
    fun newRunId(): Long = synchronized(lock) { ++runCounter }

    /**
     * Starts a standalone run with the given configs.
     *
     * A run that is already active is stopped first, so two runs never check the same
     * config at the same time. The configs are checked with
     * [ServiceCheckTuning.YT_CONCURRENCY_STANDALONE] checks at the same time.
     *
     * @param guids The GUIDs of the profiles.
     */
    fun start(guids: List<String>) {
        val unique = guids.asSequence().filter { it.isNotBlank() }.distinct().toList()
        val runId = newRunId()
        if (unique.isEmpty()) {
            synchronized(lock) {
                _state.value = ServiceCheckRunState(runId = runId)
            }
            return
        }
        val jobs = beginRun(runId, ServiceCheckTuning.YT_CONCURRENCY_STANDALONE)
        // Every config of a standalone run has the same delay, they are checked in the
        // order of the list.
        unique.forEach { enqueue(it, 0L) }
        endInput()
        jobs.forEach { it.start() }
    }

    /**
     * Starts a pipeline run.
     *
     * The configs are added while the run is going with [enqueue]. The run ends when
     * [endInput] was called and every config that was added was checked.
     *
     * @param runId The id of the run. The events of the run carry it.
     */
    fun beginPipeline(runId: Long) {
        val jobs = beginRun(runId, ServiceCheckTuning.YT_CONCURRENCY_PIPELINE)
        jobs.forEach { it.start() }
    }

    /**
     * Adds one config to the running run.
     *
     * The config with the lowest real delay is checked first. A config that is already
     * part of the run is ignored, so a config is never checked twice.
     *
     * @param guid The GUID of the profile.
     * @param delayMillis The real delay of the profile.
     */
    fun enqueue(guid: String, delayMillis: Long) {
        if (guid.isBlank()) return
        val queued = synchronized(lock) {
            if (runOver || !seenGuids.add(guid)) {
                false
            } else {
                pendingChecks.add(PendingCheck(guid, delayMillis, sequenceCounter++))
                _state.update { it.copy(isRunning = true, total = it.total + 1) }
                true
            }
        }
        if (queued) {
            workSignal.trySend(Unit)
        }
    }

    /**
     * Closes the input of the running run.
     *
     * No more configs are added after this call. The run ends when the queue is empty
     * and every worker is idle.
     */
    fun endInput() {
        synchronized(lock) {
            inputClosed = true
            _state.update { it.copy(inputClosed = true) }
            finishRunIfDone()
            wakeWorkers()
        }
    }

    /**
     * Stops the running check.
     *
     * The temporary cores of the running checks are stopped by the runner.
     */
    fun cancel() {
        synchronized(lock) {
            stopWorkers()
            pendingChecks.clear()
            seenGuids.clear()
            runOver = true
            inputClosed = true
            _state.update { it.copy(isRunning = false, inputClosed = true) }
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
            pendingChecks.clear()
            seenGuids.clear()
            runOver = true
            inputClosed = true
            _state.update { it.copy(isRunning = false, inputClosed = true) }
            stopped
        }
        jobs.joinAll()
    }

    /**
     * Prepares a new run and creates its workers.
     *
     * The workers are not started yet, so the state of the run is complete before the
     * first config is checked.
     *
     * @param runId The id of the run.
     * @param concurrency The number of workers of the run.
     * @return The workers of the run, they are not started yet.
     */
    private fun beginRun(runId: Long, concurrency: Int): List<Job> = synchronized(lock) {
        // The previous run is stopped before the new workers are started, so two runs
        // never check the same config at the same time.
        stopWorkers()
        pendingChecks.clear()
        seenGuids.clear()
        sequenceCounter = 0L
        activeWorkers = 0
        runOver = false
        inputClosed = false
        currentRunId = runId
        // The id of the run comes from the caller, the counter stays above it so the
        // next run of this manager never uses the same id.
        runCounter = maxOf(runCounter, runId)
        _state.value = ServiceCheckRunState(isRunning = true, total = 0, runId = runId)
        // The signals of the old run are dropped, they would only wake a worker that
        // has no work yet.
        while (workSignal.tryReceive().isSuccess) {
            // The signal of the old run is dropped.
        }
        val jobs = List(concurrency) {
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    while (true) {
                        val next = takeNext(runId) ?: break
                        try {
                            checkOne(runId, next.guid)
                        } finally {
                            onWorkerIdle(runId)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Service check worker failed", e)
                }
            }
        }
        workers = jobs
        jobs
    }

    /**
     * Stops the workers of the run, the caller holds the lock.
     *
     * @return The jobs of the stopped workers.
     */
    private fun stopWorkers(): List<Job> {
        val stopped = workers
        workers = emptyList()
        stopped.forEach { it.cancel() }
        return stopped
    }

    /**
     * Takes the next config of the run.
     *
     * The config with the lowest delay is taken first. The worker waits until a config
     * is added or the run is over.
     *
     * @param runId The id of the run the worker belongs to.
     * @return The next config, null when the run is over.
     */
    private suspend fun takeNext(runId: Long): PendingCheck? {
        while (true) {
            val next = synchronized(lock) {
                if (runId != currentRunId || runOver) {
                    null
                } else {
                    val head = pendingChecks.poll()
                    if (head != null) activeWorkers++
                    head
                }
            }
            if (next != null) return next
            if (synchronized(lock) { runId != currentRunId || runOver }) return null
            workSignal.receive()
        }
    }

    /**
     * Marks a worker as idle and ends the run when it was the last one.
     *
     * @param runId The id of the run the worker belongs to.
     */
    private fun onWorkerIdle(runId: Long) {
        synchronized(lock) {
            // A worker of an old run must not change the state of the current run.
            if (runId != currentRunId) return
            activeWorkers--
            finishRunIfDone()
        }
    }

    /**
     * Ends the run when the input is closed, the queue is empty and every worker is
     * idle. The caller holds the lock.
     */
    private fun finishRunIfDone() {
        if (!inputClosed) return
        if (pendingChecks.isNotEmpty()) return
        if (activeWorkers > 0) return
        runOver = true
        _state.update { it.copy(isRunning = false) }
        wakeWorkers()
    }

    /**
     * Wakes the workers that wait for work.
     *
     * Every worker gets a signal, so all of them leave their wait when the run is over.
     */
    private fun wakeWorkers() {
        repeat(workers.size) {
            workSignal.trySend(Unit)
        }
    }

    /**
     * Checks one config and writes its report to the service check store.
     *
     * @param runId The id of the run the check belongs to.
     * @param guid The GUID of the profile.
     */
    private suspend fun checkOne(runId: Long, guid: String) {
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
        val isYtOk = ServiceCheckRunner.isYtOk(report)
        synchronized(lock) {
            // The run was replaced while the config was checked, the state of the
            // current run must not change.
            if (runId != currentRunId) return
            _state.update { current ->
                current.copy(
                    done = current.done + 1,
                    goodCount = current.goodCount + if (isYtOk) 1 else 0,
                )
            }
        }
        // The event is sent after the report is in the store, so a screen that reads the
        // store right after the event finds the same report.
        _events.tryEmit(
            ServiceCheckEvent(
                runId = runId,
                guid = guid,
                report = report,
                isYtOk = isYtOk,
            )
        )
    }
}
