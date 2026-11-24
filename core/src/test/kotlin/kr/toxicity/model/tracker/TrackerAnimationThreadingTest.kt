/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.tracker

import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.BetterModelConfig
import kr.toxicity.model.api.BetterModelPlatform
import kr.toxicity.model.api.BetterModelEvaluator
import kr.toxicity.model.api.BetterModelEventBus
import kr.toxicity.model.api.animation.keyframe.KeyframeRuntimeContext
import kr.toxicity.model.api.tracker.DummyTracker
import kr.toxicity.model.api.animation.*
import kr.toxicity.model.api.bone.*
import kr.toxicity.model.api.config.DebugConfig
import kr.toxicity.model.api.data.Float3
import kr.toxicity.model.api.data.blueprint.*
import kr.toxicity.model.api.data.renderer.*
import kr.toxicity.model.api.manager.SkinManager
import kr.toxicity.model.api.nms.*
import kr.toxicity.model.api.platform.*
import kr.toxicity.model.api.skin.SkinData
import kr.toxicity.model.api.tracker.Tracker
import kr.toxicity.model.api.tracker.TrackerModifier
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import kotlin.test.*

class TrackerAnimationThreadingTest {
    private val platformField = BetterModel::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var previousPlatform: Any? = null
    private val skin = stub<SkinData> { error("Unexpected skin access: $it") }
    private val location = stub<PlatformLocation> { error("Unexpected location access: $it") }
    private val bundler = object : PacketBundler {
        override fun isEmpty() = true
        override fun size() = 0
        override fun send(player: PlatformPlayer, onSuccess: Runnable) = error("No viewers in this test")
    }
    private val air = object : PlatformItemStack {
        override fun isAir() = true
        override fun clone() = this
        override fun enchant(enchant: Boolean) = this
        override fun itemModel(namespace: PlatformNamespace?) = this
    }

    @BeforeTest
    fun initializePlatform() {
        previousPlatform = platformField.get(null)
        val config = stub<BetterModelConfig> {
            when (it) {
                "packetBundlingSize" -> 0
                "debug" -> DebugConfig.DEFAULT
                else -> error("Unexpected config access: $it")
            }
        }
        val adapter = stub<PlatformAdapter> { if (it == "air") air else error(it) }
        val skins = stub<SkinManager> { if (it == "fallback") skin else error(it) }
        val mod = stub<ModAnimationBundler> { error("Unexpected mod bundler access: $it") }
        val nms = stub<NMS> {
            when (it) {
                "createBundler" -> bundler
                "createModAnimationBuilder" -> mod
                else -> error("Unexpected NMS access: $it")
            }
        }
        val evaluator = stub<BetterModelEvaluator> { if (it == "newContext") KeyframeRuntimeContext.empty() else error(it) }
        val events = stub<BetterModelEventBus> { if (it == "call") BetterModelEventBus.Result.SUCCESS else error(it) }
        BetterModel.register(stub<BetterModelPlatform> {
            when (it) {
                "config" -> config
                "adapter" -> adapter
                "manager" -> skins
                "nms" -> nms
                "evaluator" -> evaluator
                "eventBus" -> events
                else -> error("Unexpected platform access: $it")
            }
        })
    }

    @AfterTest
    fun restorePlatform() {
        platformField.set(null, previousPlatform)
    }

    @Test
    fun `starting an animation leaves clock advancement to the worker`() {
        val tracker = tracker()
        assertCallerDoesNotTick(tracker) { tracker.animate("test") }
        workerTick(tracker)
        assertEquals("test", tracker.pipeline.runningAnimation()?.name())
    }

    @Test
    fun `stopping an animation leaves clock advancement to the worker`() {
        val tracker = tracker()
        assertTrue(tracker.animate("test"))
        workerTick(tracker)
        assertCallerDoesNotTick(tracker) { tracker.stopAnimation("test") }
    }

    @Test
    fun `replacing an animation leaves clock advancement to the worker`() {
        val tracker = tracker()
        assertTrue(tracker.animate("test"))
        workerTick(tracker)
        assertCallerDoesNotTick(tracker) { tracker.replace("test", "replacement", AnimationModifier.DEFAULT) }
        val before = clock(tracker)
        workerTick(tracker)
        assertEquals(before - 1, clock(tracker))
    }

    private fun tracker(): Tracker {
        val source = RenderSource.of(location)
        val group = BlueprintElement.Group(UUID.randomUUID(), BoneName(emptySet(), "test", "test"), Float3.ZERO, Float3.ZERO, emptyList(), true)
        val bone = RendererGroup(1F, air, group, linkedMapOf(), null, BoneItemMapper.EMPTY)
            .create(BoneRenderContext(source, skin))
        fun animation(name: String) = BlueprintAnimation(name, AnimationIterator.Type.LOOP, 0.1F, false, emptyMap(), null, AnimationProgress.emptyStorage(0.1F))
        val renderer = ModelRenderer("test", ModelRenderer.Type.GENERAL, linkedMapOf(), mapOf("test" to animation("test"), "replacement" to animation("replacement")))
        return DummyTracker(location, RenderPipeline(renderer, source, arrayOf(bone)), TrackerModifier(false, false, false)) {}.also {
            // Represent a scheduled tracker paused between worker ticks without starting its executor.
            Tracker::class.java.getDeclaredField("task").apply { isAccessible = true }.set(it,
                stub<ScheduledFuture<*>> { method -> if (method == "isCancelled") false else error(method) })
        }
    }

    private fun assertCallerDoesNotTick(tracker: Tracker, action: () -> Boolean) {
        val before = clock(tracker)
        assertTrue(action())
        assertEquals(before, clock(tracker), "An API caller must not advance the worker's animation clock")
    }

    private fun workerTick(tracker: Tracker) = Tracker::class.java.getDeclaredMethod("animationTick")
        .apply { isAccessible = true }.invoke(tracker)

    private fun clock(tracker: Tracker) = (Tracker::class.java.getDeclaredField("scriptProcessor")
        .apply { isAccessible = true }.get(tracker) as AnimationStateHandler<*>).delay

    private inline fun <reified T> stub(crossinline answer: (String) -> Any?): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)
    ) { _, method, _ -> answer(method.name) } as T
}
