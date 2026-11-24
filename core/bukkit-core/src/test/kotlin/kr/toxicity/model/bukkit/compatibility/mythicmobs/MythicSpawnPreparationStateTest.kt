/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.compatibility.mythicmobs

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.*

class MythicSpawnPreparationStateTest {
    private val entity = Any()
    private val type = Any()

    @Test
    fun oldFailureCannotFinishReplacement() {
        val old = MythicSpawnPreparationState(entity, type, 1)
        val replacement = MythicSpawnPreparationState(entity, type, 1)
        var current = old
        val observation = old.observe(entity, type, 1, isSpawn = true, { current === old }) {
            old.complete {}
        }!!
        current = replacement
        observation()
        assertFalse(replacement.ready.isDone)
        assertFalse(old.ready.isDone)
    }

    @Test
    fun mismatchedEntityTypeEpochAndTriggerAreRejected() {
        val state = MythicSpawnPreparationState(entity, type, 1)
        fun observe(e: Any, t: Any, epoch: Long, spawn: Boolean) =
            state.observe(e, t, epoch, isSpawn = spawn, { true }) { error("Unexpected failure callback") }
        assertNull(observe(Any(), type, 1, true))
        assertNull(observe(entity, Any(), 1, true))
        assertNull(observe(entity, type, 2, true))
        assertNull(observe(entity, type, 1, false))
        assertNull(state.observe(entity, type, 1, isSpawn = true, { false }) {})
    }

    @Test
    fun oldSuccessMarkerAndOldTypeCannotMatchAfterReload() {
        val reloadedType = Any()
        val replacement = MythicSpawnPreparationState(entity, reloadedType, 2)
        assertFalse(replacement.matches(entity, type, 1))
        assertFalse(replacement.matches(entity, reloadedType, 1))
        assertNull(replacement.observe(entity, type, 2, isSpawn = true, { true }) {})
        assertFalse(replacement.ready.isDone)
    }

    @Test
    fun resetAfterBeginInvalidatesCapturedObservation() {
        val state = MythicSpawnPreparationState(entity, type, 1)
        var current = true
        val observer = state.observe(entity, type, 1, isSpawn = true, { current }) { state.complete {} }!!
        current = false
        observer()
        assertFalse(state.ready.isDone)
    }

    @Test
    fun oneEntityFailureLeavesAnotherPending() {
        val failed = MythicSpawnPreparationState(entity, type, 1)
        val healthy = MythicSpawnPreparationState(Any(), type, 1)
        failed.observe(entity, type, 1, isSpawn = true, { true }) { failed.complete {} }!!()
        assertTrue(failed.ready.isDone)
        assertFalse(healthy.ready.isDone)
    }

    @Test
    fun requestDoesNotSkipQueuedBusinessCleanup() {
        val state = MythicSpawnPreparationState(entity, type, 1)
        assertTrue(state.requestCompletion())
        assertFalse(state.requestCompletion())
        assertFalse(state.ready.isDone)
        var models = 0
        var removed = 0
        val ownerQueue = ArrayDeque<() -> Unit>()
        ownerQueue.add { models++ }
        ownerQueue.add { state.complete { if (models == 0) removed++ } }
        ownerQueue.forEach { it() }
        assertEquals(1, models)
        assertEquals(0, removed)
        assertTrue(state.ready.isDone)
    }

    @Test
    fun concurrentCompletionCleansAndCancelsExactlyOnce() {
        val state = MythicSpawnPreparationState(entity, type, 1)
        val cleaned = AtomicInteger()
        val canceled = AtomicInteger()
        state.timeout { canceled.incrementAndGet() }
        val start = CountDownLatch(1)
        val workers = List(8) { thread { start.await(); state.complete { cleaned.incrementAndGet() } } }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals(1, cleaned.get())
        assertEquals(1, canceled.get())
        assertTrue(state.ready.isDone)
    }

    @Test
    fun completionBeforeTimeoutInstallationCancelsLateTimeout() {
        val state = MythicSpawnPreparationState(entity, type, 1)
        state.complete {}
        var canceled = 0
        state.timeout { canceled++ }
        assertEquals(1, canceled)
    }

    @Test
    fun externalRegistryClosePreventsLaterCleanup() {
        val state = MythicSpawnPreparationState(entity, type, 1)
        state.ready.complete(null)
        assertFalse(state.requestCompletion())
        state.complete { error("Registry already closed") }
    }

    @Test
    fun cleanupExceptionStillReleasesReadinessAndTimeout() {
        val state = MythicSpawnPreparationState(entity, type, 1)
        var canceled = 0
        state.timeout { canceled++ }
        assertFailsWith<IllegalStateException> { state.complete { error("Injected cleanup failure") } }
        assertTrue(state.ready.isDone)
        assertEquals(1, canceled)
    }
}
