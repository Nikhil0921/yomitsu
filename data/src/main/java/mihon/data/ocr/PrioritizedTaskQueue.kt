package mihon.data.ocr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    }

    private data class ActiveTask(
        val id: Int,
        val priority: Priority,
        val label: String?,
        val enqueuedAt: Long,
        val startedAt: Long,
    )

    private val mutex = Mutex()
    private val highPriorityTasks = ArrayDeque<suspend () -> Unit>()
    private val normalPriorityTasks = ArrayDeque<suspend () -> Unit>()

    private var activeTasks = 0
    private var workerJob: Job? = null

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

        val task: suspend () -> Unit = {
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
            }
            if (tachiyomi.data.BuildConfig.DEBUG) {
                logcat(LogPriority.DEBUG) {
                    "OCR queue enqueue task=${System.identityHashCode(result)} priority=$priority " +
                        "label=$diagnosticLabel enqueueNs=$enqueuedAt activeSlots=${snapshotActive()}"
                }
            }
            logcat(LogPriority.DEBUG) {
                "OCR queue depth high=${highPriorityTasks.size} normal=${normalPriorityTasks.size} " +
                    "active=$activeTasks/$maxConcurrentTasks"
            }
            if (workerJob?.isActive != true && activeTasks < maxConcurrentTasks) {
                workerJob = scope.launch { processQueue() }
            }
        }

        return result.await()
    }

    suspend fun isIdle(): Boolean {
        return mutex.withLock {
            activeTasks == 0 && highPriorityTasks.isEmpty() && normalPriorityTasks.isEmpty()
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
                    val nextTask = highPriorityTasks.removeFirstOrNull()
                        ?: normalPriorityTasks.removeFirstOrNull()
                    if (nextTask != null) activeTasks++
                    nextTask
                }
            } ?: break

            // Launch instead of running inline: up to maxConcurrentTasks tasks overlap
            // (remote GLENS scans are network-bound). The queue task itself owns its
            // bitmap lifecycle, so an abandoned await never cancels the running scan.
            scope.launch {
                try {
                    task()
                } finally {
                    val becameIdle = mutex.withLock {
                        activeTasks--
                        activeTasks == 0 && highPriorityTasks.isEmpty() && normalPriorityTasks.isEmpty()
                    }
                    if (becameIdle) {
                        onIdle()
                    }
                    mutex.withLock {
                        if (workerJob?.isActive != true &&
                            activeTasks < maxConcurrentTasks &&
                            (highPriorityTasks.isNotEmpty() || normalPriorityTasks.isNotEmpty())
                        ) {
                            workerJob = scope.launch { processQueue() }
                        }
                    }
                }
            }
        }
    }
}
