/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.compatibility.mythicmobs

import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class MythicDispatchObservationRetentionTest {
    private val fixture = DispatchApiFixture::class.java
    private val context = DispatchApiFixture.Context(UUID.randomUUID(), UUID.randomUUID(), Any(), Any(), Any())
    private val next = AtomicReference<MythicSpawnPreparationState?>()
    private val failures = AtomicInteger()
    private val failuresByState = ConcurrentHashMap<MythicSpawnPreparationState, AtomicInteger>()

    @BeforeTest
    fun resetFixture() {
        DispatchApiFixture.version = 1
        DispatchApiFixture.registrations = 0
        DispatchApiFixture.closures = 0
    }

    private fun subscribe(completeDuringBegin: Boolean = false): AutoCloseable = MythicDispatchObservation.subscribe(
        fixture.classLoader, Any(), { value ->
            val state = next.getAndSet(null)!!
            val action = state.observe(value.entity, value.mobType, 1, isSpawn = true, { true }) {
                failures.incrementAndGet()
                failuresByState.computeIfAbsent(state) { AtomicInteger() }.incrementAndGet()
                state.complete {}
            }
            if (completeDuringBegin) state.ready.complete(null)
            action
        }, { _, error -> throw AssertionError("Unexpected API diagnostic", error) }, fixture.name
    )!!

    private fun state() = MythicSpawnPreparationState(context.entity(), context.mobType(), 1)

    private fun install(state: MythicSpawnPreparationState): DispatchApiFixture.Observation {
        next.set(state)
        return DispatchApiFixture.observer.begin(context)
    }

    private fun pendingCount(): Int {
        val handler = Proxy.getInvocationHandler(DispatchApiFixture.observer)
        return handler.javaClass.declaredFields.map {
            it.isAccessible = true
            it.get(handler)
        }.filterIsInstance<Set<*>>().single().size
    }

    private fun completedObservation(): Pair<WeakReference<MythicSpawnPreparationState>, DispatchApiFixture.Observation> {
        val state = state()
        val observation = install(state)
        state.complete {}
        return WeakReference(state) to observation
    }

    private fun alive(references: List<WeakReference<MythicSpawnPreparationState>>): Int {
        repeat(8) {
            System.gc()
            Thread.sleep(20)
        }
        return references.count { it.get() != null }
    }

    @Test
    fun completedPreparationsAreReleasedWithoutFinishOrUnsubscribe() {
        subscribe().use {
            val observations = List(100) { completedObservation() }
            assertEquals(0, pendingCount())
            assertEquals(0, alive(observations.map { it.first }))
            observations.forEach { it.second.finish(failed()) }
            assertEquals(0, failures.get())
            assertEquals(0, DispatchApiFixture.closures)
        }
    }

    @Test
    fun readyCompletingDuringBeginCannotLeaveInstalledEntry() {
        subscribe(completeDuringBegin = true).use {
            val observation = install(state())
            assertEquals(0, pendingCount())
            observation.finish(failed())
            assertEquals(0, failures.get())
        }
    }

    @Test
    fun concurrentInstallationAndReadyCompletionLeaveNoEntries() {
        subscribe().use {
            Executors.newFixedThreadPool(2).use { pool ->
                repeat(1000) {
                    val state = state()
                    next.set(state)
                    val start = CountDownLatch(1)
                    val installed = pool.submit<DispatchApiFixture.Observation> {
                        start.await()
                        DispatchApiFixture.observer.begin(context)
                    }
                    val completed = pool.submit { start.await(); state.ready.complete(null) }
                    start.countDown()
                    val observation = installed.get()
                    completed.get()
                    assertEquals(0, pendingCount())
                    observation.finish(failed())
                }
            }
            assertEquals(0, failures.get())
        }
    }

    @Test
    fun lateFailureAfterExternalCloseLeavesReplacementPending() {
        subscribe().use {
            val old = state()
            val stale = install(old)
            old.ready.complete(null)
            assertEquals(0, pendingCount())
            val replacement = state()
            val current = install(replacement)
            assertEquals(1, pendingCount())
            stale.finish(failed())
            assertEquals(0, failures.get())
            assertFalse(replacement.ready.isDone)
            current.finish(failed())
            assertTrue(replacement.ready.isDone)
            assertEquals(1, failures.get())
            assertEquals(0, pendingCount())
        }
    }

    @Test
    fun healthySlowPreparationRemainsObservedUntilCompletion() {
        subscribe().use {
            val state = state()
            val observation = install(state)
            assertFalse(state.ready.isDone)
            assertEquals(1, pendingCount())
            observation.finish(failed())
            observation.finish(failed())
            assertTrue(state.ready.isDone)
            assertEquals(1, failures.get())
            assertEquals(0, pendingCount())
        }
    }

    @Test
    fun finishAndReadyRaceReleasesAllEntriesWithAtMostOneFailure() {
        subscribe().use {
            val states = List(1000) { state() }
            val observations = states.map { install(it) }
            val start = CountDownLatch(1)
            Executors.newFixedThreadPool(8).use { pool ->
                val jobs = mutableListOf<Future<*>>()
                states.forEachIndexed { index, state ->
                    jobs.add(pool.submit { start.await(); state.ready.complete(null) })
                    jobs.add(pool.submit { start.await(); observations[index].finish(failed()) })
                    jobs.add(pool.submit { start.await(); observations[index].finish(failed()) })
                }
                start.countDown()
                jobs.forEach { it.get() }
            }
            assertTrue(states.all { it.ready.isDone })
            assertTrue(failures.get() <= states.size)
            assertTrue(failuresByState.values.all { it.get() == 1 })
            assertEquals(0, pendingCount())
        }
    }

    @Test
    fun readyFinishAndUnsubscribeRaceIsIdempotent() {
        val registration = subscribe()
        val states = List(100) { state() }
        val observations = states.map { install(it) }
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(3).use { pool ->
            val jobs = listOf(
                pool.submit { start.await(); states.forEach { it.ready.complete(null) } },
                pool.submit { start.await(); observations.forEach { it.finish(failed()) } },
                pool.submit { start.await(); registration.close() }
            )
            start.countDown()
            jobs.forEach { it.get() }
        }
        registration.close()
        assertTrue(states.all { it.ready.isDone })
        assertTrue(failures.get() <= states.size)
        assertTrue(failuresByState.values.all { it.get() == 1 })
        assertEquals(0, pendingCount())
        assertEquals(1, DispatchApiFixture.closures)
    }

    @Test
    fun finishBeforeReadyDoesNotRetainStateThroughObservationShell() {
        subscribe().use {
            fun finishedObservation(): Pair<WeakReference<MythicSpawnPreparationState>, DispatchApiFixture.Observation> {
                val state = state()
                val observation = install(state)
                observation.finish(DispatchApiFixture.Outcome(DispatchApiFixture.Status.COMPLETED, null))
                assertFalse(state.ready.isDone)
                return WeakReference(state) to observation
            }
            val observations = List(20) { finishedObservation() }
            assertEquals(0, pendingCount())
            assertEquals(0, alive(observations.map { it.first }))
            observations.forEach { it.second.finish(failed()) }
            assertEquals(0, failures.get())
        }
    }

    private fun failed() = DispatchApiFixture.Outcome(DispatchApiFixture.Status.FAILED, IllegalStateException("test"))
}
