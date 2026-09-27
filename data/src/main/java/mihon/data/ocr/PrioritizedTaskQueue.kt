package mihon.data.ocr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

internal class PrioritizedTaskQueue(
    private val scope: CoroutineScope,
    private val maxConcurrentTasks: Int = 3,
    private val onIdle: () -> Unit = {},
) {
    enum class Priority {
        HIGH,
        NORMAL,
        LOW,
    }

    /**
     * A queued task plus the job that asked for it. The task is launched as a child of that job so
     * cancelling the submitter cancels the work, instead of leaving it running to completion in the
     * queue's long-lived scope.
     */
    private class QueuedTask(
        val owner: Job?,
        val run: suspend () -> Unit,
    )

    private data class ActiveTask(
        val id: Int,
        val priority: Priority,
        val label: String?,
        val enqueuedAt: Long,
        val startedAt: Long,
    )

    private val mutex = Mutex()
    private val highPriorityTasks = ArrayDeque<QueuedTask>()
    private val normalPriorityTasks = ArrayDeque<QueuedTask>()
    private val lowPriorityTasks = ArrayDeque<QueuedTask>()

    private var activeTasks = 0
    private var workerJob: Job? = null

    /**
     * NORMAL/LOW stop one slot short of [maxConcurrentTasks] so a HIGH task always finds a
     * free slot and never queues behind background `OcrScanJob`/prefetch scans. With a single
     * slot there is nothing to reserve, so background keeps it.
     */
    private val backgroundSlotCeiling: Int =
        if (maxConcurrentTasks > 1) maxConcurrentTasks - 1 else maxConcurrentTasks

    // DEBUG-only: identity of every task currently occupying a queue slot, so a
    // newly-enqueued task can see exactly what is holding the slots it waits for.
    private val activeTaskDetails = LinkedHashMap<Int, ActiveTask>()

    private fun snapshotActive(): String =
        if (activeTaskDetails.isEmpty()) {
            "none"
        } else {
            activeTaskDetails.values.joinToString("; ") {
                "${it.id}@${it.priority}${it.label?.let { l -> " $l" } ?: ""} " +
                    "waitMs=${(it.startedAt - it.enqueuedAt) / 1_000_000}"
            }
        }

    suspend fun <T> submit(
        priority: Priority,
        diagnosticLabel: String? = null,
        block: suspend () -> T,
    ): T {
        val result = CompletableDeferred<T>()
        var enqueuedAt = 0L
        // The job that asked for this work. Captured here because the task body runs later, on a
        // coroutine the submitter has no reference to.
        val owner = currentCoroutineContext()[Job]

        val task = QueuedTask(owner) {
            if (!result.isCancelled) {
                val startedAt = if (tachiyomi.data.BuildConfig.DEBUG) System.nanoTime() else 0L
                val taskId = System.identityHashCode(result)
                if (tachiyomi.data.BuildConfig.DEBUG) {
                    mutex.withLock {
                        activeTaskDetails[taskId] = ActiveTask(
                            id = taskId,
                            priority = priority,
                            label = diagnosticLabel,
                            enqueuedAt = enqueuedAt,
                            startedAt = startedAt,
                        )
                    }
                    logcat(LogPriority.DEBUG) {
                        "OCR queue start task=$taskId priority=$priority " +
                            "label=$diagnosticLabel enqueueNs=$enqueuedAt startNs=$startedAt " +
                            "waitMs=${(startedAt - enqueuedAt) / 1_000_000}"
                    }
                }
                try {
                    result.complete(block())
                } catch (e: Throwable) {
                    result.completeExceptionally(e)
                } finally {
                    if (tachiyomi.data.BuildConfig.DEBUG) {
                        mutex.withLock { activeTaskDetails.remove(taskId) }
                    }
                }
            }
        }

        mutex.withLock {
            enqueuedAt = if (tachiyomi.data.BuildConfig.DEBUG) System.nanoTime() else 0L
            when (priority) {
                Priority.HIGH -> highPriorityTasks.addLast(task)
                Priority.NORMAL -> normalPriorityTasks.addLast(task)
                Priority.LOW -> lowPriorityTasks.addLast(task)
            }
            if (tachiyomi.data.BuildConfig.DEBUG) {
                logcat(LogPriority.DEBUG) {
                    "OCR queue enqueue task=${System.identityHashCode(result)} priority=$priority " +
                        "label=$diagnosticLabel enqueueNs=$enqueuedAt activeSlots=${snapshotActive()}"
                }
            }
            logcat(LogPriority.DEBUG) {
                "OCR queue depth high=${highPriorityTasks.size} normal=${normalPriorityTasks.size} " +
                    "low=${lowPriorityTasks.size} active=$activeTasks/$maxConcurrentTasks"
            }
            if (workerJob?.isActive != true && activeTasks < maxConcurrentTasks) {
                workerJob = scope.launch { processQueue() }
            }
        }

        return result.await()
    }

    suspend fun isIdle(): Boolean {
        return mutex.withLock {
            activeTasks == 0 && highPriorityTasks.isEmpty() && normalPriorityTasks.isEmpty() &&
                lowPriorityTasks.isEmpty()
        }
    }

    private suspend fun processQueue() {
        while (true) {
            val task = mutex.withLock {
                if (activeTasks >= maxConcurrentTasks) {
                    // Full: a finishing task restarts the drain loop when capacity frees.
                    workerJob = null
                    null
                } else {
                    val high = highPriorityTasks.removeFirstOrNull()
                    when {
                        // HIGH ignores the background ceiling: the slot it would have waited
                        // for is the one reserved for it.
                        high != null -> high.also { activeTasks++ }
                        activeTasks >= backgroundSlotCeiling -> {
                            // Only background work is left and its slots are taken. Hold the
                            // reservation for a HIGH task; a finishing task restarts the loop.
                            workerJob = null
                            null
                        }
                        else -> (
                            normalPriorityTasks.removeFirstOrNull()
                                ?: lowPriorityTasks.removeFirstOrNull()
                            )?.also { activeTasks++ }
                    }
                }
            } ?: break

            // Launch instead of running inline: up to maxConcurrentTasks tasks overlap
            // (remote GLENS scans are network-bound).
            //
            // The task is parented to the SUBMITTER's job, not to [scope], so cancelling the
            // caller cancels the work it asked for. It used to be the other way round: every task
            // ran to completion in the queue's long-lived scope, so a `withTimeoutOrNull` around a
            // scan *abandoned* the upload instead of stopping it — the caller counted the page as
            // skipped while a doomed 100 s GLENS call kept holding a background slot
            // (docs/audits/full-ocr-pipeline-audit.md §2, F1.6) — and `prefetchJob.cancel()`
            // could not stop already-queued scans, which is what produced the 29% duplicate-scan
            // enqueues. The submitter's own bitmap lifecycle and cache write are unaffected either
            // way, because they live inside the block and run in its `finally`.
            val taskContext = if (task.owner != null) {
                scope.coroutineContext + task.owner
            } else {
                scope.coroutineContext
            }
            CoroutineScope(taskContext).launch {
                try {
                    task.run()
                } finally {
                    val becameIdle = mutex.withLock {
                        activeTasks--
                        activeTasks == 0 && highPriorityTasks.isEmpty() && normalPriorityTasks.isEmpty() &&
                            lowPriorityTasks.isEmpty()
                    }
                    if (becameIdle) {
                        onIdle()
                    }
                    mutex.withLock {
                        if (workerJob?.isActive != true &&
                            activeTasks < maxConcurrentTasks &&
                            (
                                highPriorityTasks.isNotEmpty() || normalPriorityTasks.isNotEmpty() ||
                                    lowPriorityTasks.isNotEmpty()
                                )
                        ) {
                            workerJob = scope.launch { processQueue() }
                        }
                    }
                }
            }
        }
    }
}
