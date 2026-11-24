/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.compatibility.mythicmobs

import java.util.UUID
import kotlin.test.*

class MythicDispatchObservationTest {
    private val fixture = DispatchApiFixture::class.java
    private val warnings = mutableListOf<String>()
    private var failures = 0
    private val context = DispatchApiFixture.Context(UUID.randomUUID(), UUID.randomUUID(), Any(), Any(), Any())

    @BeforeTest
    fun resetFixture() {
        DispatchApiFixture.version = 1
        DispatchApiFixture.registrations = 0
        DispatchApiFixture.closures = 0
        DispatchApiFixture.observer = null
    }

    private fun subscribe(name: String = fixture.name) = MythicDispatchObservation.subscribe(
        fixture.classLoader, Any(), { captured ->
            assertEquals(context.dispatchId(), captured.dispatchId)
            assertEquals(context.entityId(), captured.entityId)
            assertSame(context.entity(), captured.entity)
            assertSame(context.mobType(), captured.mobType)
            assertSame(context.trigger(), captured.trigger)
            val onFailure: () -> Unit = { failures++ }
            onFailure
        }, { message, _ -> warnings.add(message) }, name
    )

    @Test
    fun oldApiMissingKeepsExistingPath() {
        assertNull(subscribe("missing.mm.RootSkillDispatchObservers"))
        assertEquals(0, DispatchApiFixture.registrations)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun unsupportedVersionDoesNotSubscribe() {
        DispatchApiFixture.version = 2
        assertNull(subscribe())
        assertEquals(0, DispatchApiFixture.registrations)
        assertEquals(1, warnings.size)
    }

    @Test
    fun supportedApiRegistersOnceAndCloseIsIdempotent() {
        val registration = subscribe()!!
        assertEquals(1, DispatchApiFixture.registrations)
        registration.close()
        registration.close()
        assertEquals(1, DispatchApiFixture.closures)
    }

    @Test
    fun onlyFailedWithErrorFinishesOnce() {
        subscribe()!!.use {
            for (status in listOf(DispatchApiFixture.Status.COMPLETED, DispatchApiFixture.Status.SKIPPED)) {
                DispatchApiFixture.observer.begin(context).finish(DispatchApiFixture.Outcome(status, null))
            }
            val observation = DispatchApiFixture.observer.begin(context)
            val failed = DispatchApiFixture.Outcome(DispatchApiFixture.Status.FAILED, IllegalStateException("test"))
            observation.finish(failed)
            observation.finish(failed)
            assertEquals(1, failures)
        }
    }

    @Test
    fun unsubscribeRevokesOutstandingAndNewObservations() {
        val registration = subscribe()!!
        val observer = DispatchApiFixture.observer
        val outstanding = observer.begin(context)
        registration.close()
        val failed = DispatchApiFixture.Outcome(DispatchApiFixture.Status.FAILED, IllegalStateException("test"))
        outstanding.finish(failed)
        observer.begin(context).finish(failed)
        observer.begin(null).finish(failed)
        assertEquals(0, failures)
    }

    @Test
    fun unmatchedPreparationUsesProviderNoopObservation() {
        val registration = MythicDispatchObservation.subscribe(
            fixture.classLoader, Any(), { null }, { _, _ -> error("Unexpected warning") }, fixture.name
        )!!
        registration.use {
            assertSame(DispatchApiFixture.Observation.NONE, DispatchApiFixture.observer.begin(context))
        }
    }

    @Test
    fun failedWithoutErrorIsDiagnosedAndIgnored() {
        subscribe()!!.use {
            DispatchApiFixture.observer.begin(context).finish(DispatchApiFixture.Outcome(DispatchApiFixture.Status.FAILED, null))
            assertEquals(0, failures)
            assertEquals(1, warnings.size)
        }
    }

    @Test
    fun incompatibleLayoutIsDiagnosed() {
        assertNull(subscribe(String::class.java.name))
        assertEquals(1, warnings.size)
    }
}
