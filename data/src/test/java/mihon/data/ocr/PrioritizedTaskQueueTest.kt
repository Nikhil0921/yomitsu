package mihon.data.ocr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrioritizedTaskQueueTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun highPriorityTaskRunsBeforeQueuedNormalTask() = runTest {
        val events = mutableListOf<String>()
        val holdFirstTask = CompletableDeferred<Unit>()
        val queue = PrioritizedTaskQueue(backgroundScope, maxConcurrentTasks = 1)

        val first = async {
            queue.submit(PrioritizedTaskQueue.Priority.NORMAL) {
                events += "normal-1-start"
                holdFirstTask.await()
                events += "normal-1-end"
            }
        }
        advanceUntilIdle()

        val second = async {
            queue.submit(PrioritizedTaskQueue.Priority.NORMAL) {
                events += "normal-2"
            }
        }
        val highPriority = async {
            queue.submit(PrioritizedTaskQueue.Priority.HIGH) {
                events += "high"
            }
        }

        holdFirstTask.complete(Unit)

        first.await()
        highPriority.await()
        second.await()

        assertEquals(
            listOf("normal-1-start", "normal-1-end", "high", "normal-2"),
            events,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun highPriorityTaskRunsBeforeLaterPageScanChunks() = runTest {
        val events = mutableListOf<String>()
        val holdFirstChunk = CompletableDeferred<Unit>()
        val queue = PrioritizedTaskQueue(backgroundScope, maxConcurrentTasks = 1)

        val pageScan = async {
            queue.submit(PrioritizedTaskQueue.Priority.NORMAL) {
                events += "region-1-start"
                holdFirstChunk.await()
                events += "region-1-end"
            }

            queue.submit(PrioritizedTaskQueue.Priority.NORMAL) {
                events += "region-2"
            }
        }

        advanceUntilIdle()

        val recognizeText = async {
            queue.submit(PrioritizedTaskQueue.Priority.HIGH) {
                events += "recognize-text"
            }
        }

        holdFirstChunk.complete(Unit)

        pageScan.await()
        recognizeText.await()

        assertTrue(events.indexOf("recognize-text") < events.indexOf("region-2"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun backgroundTasksLeaveOneSlotReservedForHigh() = runTest {
        val events = mutableListOf<String>()
        val releaseAll = CompletableDeferred<Unit>()
        val queue = PrioritizedTaskQueue(backgroundScope, maxConcurrentTasks = 3)

        val jobs = (1..3).map { i ->
            async {
                queue.submit(PrioritizedTaskQueue.Priority.NORMAL) {
                    events += "task-$i-start"
                    releaseAll.await()
                    events += "task-$i-end"
                }
            }
        }
        // Capacity 3 reserves one slot for HIGH, so background only reaches 2.
        runCurrent()
        assertEquals(2, events.count { it.endsWith("-start") })

        releaseAll.complete(Unit)
        jobs.forEach { it.await() }
        assertEquals(6, events.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun highPriorityTaskBypassesSaturatedBackgroundQueue() = runTest {
        val events = mutableListOf<String>()
        val releaseBackground = CompletableDeferred<Unit>()
        val queue = PrioritizedTaskQueue(backgroundScope, maxConcurrentTasks = 3)

        val background = (1..2).map { i ->
            async {
                queue.submit(PrioritizedTaskQueue.Priority.NORMAL) {
                    events += "normal-$i-start"
                    releaseBackground.await()
                    events += "normal-$i-end"
                }
            }
        }
        advanceUntilIdle()
        // Background has saturated its ceiling; a queued NORMAL task must wait.
        val queuedNormal = async {
            queue.submit(PrioritizedTaskQueue.Priority.NORMAL) { events += "normal-3" }
        }
        runCurrent()
        assertEquals(2, events.size)

        // HIGH takes the reserved slot immediately instead of queueing behind them.
        val high = async { queue.submit(PrioritizedTaskQueue.Priority.HIGH) { events += "high" } }
        runCurrent()
        assertTrue(events.contains("high"), "HIGH must start while background slots are full")
        assertTrue(!events.contains("normal-3"), "queued NORMAL must still be waiting")

        releaseBackground.complete(Unit)
        background.forEach { it.await() }
        queuedNormal.await()
        high.await()
        assertTrue(events.contains("normal-3"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun queuedTaskStartsWhenCapacityFrees() = runTest {
        val events = mutableListOf<String>()
        val releaseFirst = CompletableDeferred<Unit>()
        val queue = PrioritizedTaskQueue(backgroundScope, maxConcurrentTasks = 2)

        val held = async {
            queue.submit(PrioritizedTaskQueue.Priority.NORMAL) {
                events += "held-1-start"
                releaseFirst.await()
                events += "held-1-end"
            }
        }
        advanceUntilIdle()

        val overflow = async {
            queue.submit(PrioritizedTaskQueue.Priority.NORMAL) {
                events += "overflow-start"
            }
        }
        // Background ceiling for capacity 2 is 1: overflow must not have started yet.
        runCurrent()
        assertTrue(!events.contains("overflow-start"))

        releaseFirst.complete(Unit)
        held.await()
        overflow.await()

        assertTrue(events.contains("overflow-start"))
        assertEquals(listOf("held-1-start", "overflow-start"), events.filter { it.endsWith("start") })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun lowPriorityTaskDrainsAfterNormalAndHigh() = runTest {
        val events = mutableListOf<String>()
        val holdFirstTask = CompletableDeferred<Unit>()
        val queue = PrioritizedTaskQueue(backgroundScope, maxConcurrentTasks = 1)

        val first = async {
            queue.submit(PrioritizedTaskQueue.Priority.LOW) {
                events += "low-1-start"
                holdFirstTask.await()
                events += "low-1-end"
            }
        }
        advanceUntilIdle()

        val normalTask = async {
            queue.submit(PrioritizedTaskQueue.Priority.NORMAL) {
                events += "normal"
            }
        }
        val highTask = async {
            queue.submit(PrioritizedTaskQueue.Priority.HIGH) {
                events += "high"
            }
        }
        val secondLow = async {
            queue.submit(PrioritizedTaskQueue.Priority.LOW) {
                events += "low-2"
            }
        }

        holdFirstTask.complete(Unit)

        first.await()
        normalTask.await()
        highTask.await()
        secondLow.await()

        assertEquals(
            listOf("low-1-start", "low-1-end", "high", "normal", "low-2"),
            events,
        )
    }

    /**
     * A queued task is owned by whoever submitted it: cancelling that caller must cancel the work.
     *
     * The queue used to detach every task into the queue's own long-lived scope and run it to
     * completion regardless, which is why a `withTimeoutOrNull` around a scan *abandoned* the
     * upload instead of stopping it - the page was counted as skipped while a doomed 100 s GLENS
     * call kept holding a background slot (docs/audits/full-ocr-pipeline-audit.md §2, F1.6), and
     * `prefetchJob.cancel()` could not stop already-queued scans, which is what produced the 29%
     * duplicate-scan enqueues.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellingTheSubmitterCancelsTheRunningTask() = runTest {
        val started = CompletableDeferred<Unit>()
        val taskFinallyRan = CompletableDeferred<Unit>()
        val queue = PrioritizedTaskQueue(backgroundScope, maxConcurrentTasks = 1)

        val submitter = launch {
            queue.submit(PrioritizedTaskQueue.Priority.NORMAL) {
                started.complete(Unit)
                try {
                    delay(Long.MAX_VALUE)
                } finally {
                    taskFinallyRan.complete(Unit)
                }
            }
        }
        started.await()

        submitter.cancelAndJoin()

        withTimeout(5_000) { taskFinallyRan.await() }
        assertTrue(queue.isIdle(), "the cancelled task must release its slot")
    }

    /** A caller that completes normally must NOT take its task down with it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aSubmitterThatCompletesNormallyLetsItsTaskFinish() = runTest {
        val taskRan = CompletableDeferred<Unit>()
        val queue = PrioritizedTaskQueue(backgroundScope, maxConcurrentTasks = 1)

        val submitter = launch {
            queue.submit(PrioritizedTaskQueue.Priority.NORMAL) {
                taskRan.complete(Unit)
            }
        }
        submitter.join()

        withTimeout(5_000) { taskRan.await() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun isIdleReflectsRunningTasks() = runTest {
        val hold = CompletableDeferred<Unit>()
        val queue = PrioritizedTaskQueue(backgroundScope)

        val task = async {
            queue.submit(PrioritizedTaskQueue.Priority.NORMAL) {
                hold.await()
            }
        }
        advanceUntilIdle()

        assertTrue(!queue.isIdle())

        hold.complete(Unit)
        task.await()
        advanceUntilIdle()

        assertTrue(queue.isIdle())
    }
}
