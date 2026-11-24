/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bone

import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.BetterModelPlatform
import kr.toxicity.model.api.BetterModelConfig
import kr.toxicity.model.api.animation.keyframe.KeyframeRuntimeContext
import kr.toxicity.model.api.animation.AnimationIterator
import kr.toxicity.model.api.animation.AnimationModifier
import kr.toxicity.model.api.animation.AnimationOverrideState
import kr.toxicity.model.api.animation.AnimationProgress
import kr.toxicity.model.api.bone.BoneItemMapper
import kr.toxicity.model.api.bone.BoneName
import kr.toxicity.model.api.bone.BoneRenderContext
import kr.toxicity.model.api.bone.RenderedBone
import kr.toxicity.model.api.data.Float3
import kr.toxicity.model.api.data.blueprint.BlueprintAnimation
import kr.toxicity.model.api.data.blueprint.BlueprintElement
import kr.toxicity.model.api.data.renderer.RenderSource
import kr.toxicity.model.api.data.renderer.ModelRenderer
import kr.toxicity.model.api.data.renderer.RenderPipeline
import kr.toxicity.model.api.data.renderer.RendererGroup
import kr.toxicity.model.api.manager.PlayerManager
import kr.toxicity.model.api.nms.NMS
import kr.toxicity.model.api.nms.ModAnimationBundler
import kr.toxicity.model.api.nms.PacketBundler
import kr.toxicity.model.api.nms.PlayerChannelHandler
import kr.toxicity.model.api.platform.PlatformItemStack
import kr.toxicity.model.api.platform.PlatformAdapter
import kr.toxicity.model.api.platform.PlatformLocation
import kr.toxicity.model.api.platform.PlatformNamespace
import kr.toxicity.model.api.platform.PlatformPlayer
import kr.toxicity.model.api.skin.SkinData
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderedBoneLifecycleTest {
    private val air = object : PlatformItemStack {
        override fun isAir() = true
        override fun clone() = this
        override fun enchant(enchant: Boolean) = this
        override fun itemModel(namespace: PlatformNamespace?) = this
    }
    private val platformField = BetterModel::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var previousPlatform: Any? = null
    private val channels = mutableMapOf<UUID, PlayerChannelHandler>()
    private val emptyBundler = object : PacketBundler {
        override fun isEmpty() = true
        override fun size() = 0
        override fun send(player: PlatformPlayer, onSuccess: Runnable) = onSuccess.run()
    }

    @BeforeTest
    fun initializePlatform() {
        previousPlatform = platformField.get(null)
        val adapter = Proxy.newProxyInstance(
            PlatformAdapter::class.java.classLoader, arrayOf(PlatformAdapter::class.java)
        ) { _, method, _ ->
            check(method.name == "air") { "Unexpected adapter call: ${method.name}" }
            air
        } as PlatformAdapter
        val nms = Proxy.newProxyInstance(
            NMS::class.java.classLoader, arrayOf(NMS::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "createBundler" -> emptyBundler
                "createModAnimationBuilder" -> unused<ModAnimationBundler>()
                else -> error("Unexpected NMS call: ${method.name}")
            }
        } as NMS
        val playerManager = Proxy.newProxyInstance(
            PlayerManager::class.java.classLoader, arrayOf(PlayerManager::class.java)
        ) { _, method, args ->
            check(method.name == "player") { "Unexpected manager call: ${method.name}" }
            channels[args[0]]
        } as PlayerManager
        val config = Proxy.newProxyInstance(
            BetterModelConfig::class.java.classLoader, arrayOf(BetterModelConfig::class.java)
        ) { _, method, _ ->
            check(method.name == "packetBundlingSize")
            0
        } as BetterModelConfig
        BetterModel.register(Proxy.newProxyInstance(
            BetterModelPlatform::class.java.classLoader, arrayOf(BetterModelPlatform::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "adapter" -> adapter
                "nms" -> nms
                "manager" -> playerManager
                "config" -> config
                else -> error("Unexpected platform call: ${method.name}")
            }
        } as BetterModelPlatform)
    }

    @AfterTest
    fun restorePlatform() {
        platformField.set(null, previousPlatform)
    }

    @Test
    fun `targeted animations complete when every viewer has an override`() {
        targetedAnimationCompletes(withGlobalViewer = false)
    }

    @Test
    fun `targeted animations complete when the global pose has stopped updating`() {
        targetedAnimationCompletes(withGlobalViewer = true)
    }

    private fun targetedAnimationCompletes(withGlobalViewer: Boolean) {
        val bone = bone()
        val pipeline = pipeline(bone)
        val player = player()
        spawn(pipeline, player)
        if (withGlobalViewer) spawn(pipeline, player())
        val tickerType = Class.forName("kr.toxicity.model.api.tracker.TrackerAnimationTicker")
        val ticker = tickerType.getDeclaredConstructor(RenderPipeline::class.java, KeyframeRuntimeContext::class.java)
            .apply { isAccessible = true }.newInstance(pipeline, KeyframeRuntimeContext.empty())
        val add = tickerType.getDeclaredMethod("countAdd", UUID::class.java, java.util.function.Consumer::class.java)
            .apply { isAccessible = true }
        pipeline.eventDispatcher().handleStateCreate { _, uuid ->
            add.invoke(ticker, uuid, java.util.function.Consumer<PlayerChannelHandler> {})
        }
        assertTrue(bone.addAnimation(
            AnimationOverrideState.MATCHED, animation(AnimationIterator.Type.PLAY_ONCE),
            AnimationModifier.builder().player(player).build()
        ) {})
        val tick = tickerType.getDeclaredMethod("tick").apply { isAccessible = true }
        repeat(100) { tick.invoke(ticker) }
        assertTrue(playerStates(bone).isEmpty(), "The tracker worker must advance player overrides independently of the global pose")
    }

    @Test
    fun `pipeline viewer removal clears animation state before removal notification`() {
        val bone = bone()
        val pipeline = pipeline(bone)
        val first = player()
        val second = player()
        spawn(pipeline, first)
        spawn(pipeline, second)
        var removals = 0
        pipeline.eventDispatcher().handleStateRemove { _, uuid ->
            assertFalse(pipeline.isSpawned(uuid))
            removals++
        }
        addLoop(bone, first)
        addLoop(bone, second)

        assertTrue(pipeline.remove(first))
        assertFalse(pipeline.remove(first))

        assertFalse(bone.tick(first.uuid()))
        assertTrue(bone.tick(second.uuid()))
        assertEquals(1, removals)
        assertEquals(setOf(second.uuid()), playerStates(bone).keys)
    }

    @Test
    fun `pipeline despawn releases all targeted loops after forgetting viewers`() {
        val bone = bone()
        val pipeline = pipeline(bone)
        val first = player()
        val second = player()
        spawn(pipeline, first)
        spawn(pipeline, second)
        var removals = 0
        pipeline.eventDispatcher().handleStateRemove { _, uuid ->
            assertFalse(pipeline.isSpawned(uuid))
            removals++
        }
        addLoop(bone, first)
        addLoop(bone, second)

        pipeline.despawn()
        pipeline.despawn()

        assertTrue(playerStates(bone).isEmpty())
        assertEquals(2, removals)
        assertFalse(bone.tick(first.uuid()))
        assertFalse(bone.tick(second.uuid()))
    }

    @Test
    fun `removing a viewer releases targeted loop without stopping other animations`() {
        val bone = bone()
        val first = player()
        val second = player()
        val removed = mutableListOf<UUID>()
        bone.eventDispatcher().handleStateRemove { _, uuid -> removed += uuid }
        addLoop(bone, first)
        addLoop(bone, second)
        addLoop(bone, null)
        assertTrue(bone.tick(first.uuid()))
        assertTrue(bone.tick(second.uuid()))
        assertTrue(bone.tick())

        bone.removePlayerState(first.uuid())
        bone.removePlayerState(first.uuid())

        assertFalse(bone.tick(first.uuid()))
        assertEquals(listOf(first.uuid()), removed)
        assertEquals(setOf(second.uuid()), playerStates(bone).keys)
        assertTrue((0 until 100).any { bone.tick(second.uuid()) })
        assertTrue((0 until 100).any { bone.tick() })
    }

    @Test
    fun `despawn cleanup releases all viewers and allows fresh animation after returning`() {
        val bone = bone()
        val first = player()
        val second = player()
        val created = mutableListOf<UUID>()
        val removed = mutableListOf<UUID>()
        bone.eventDispatcher().handleStateCreate { _, uuid -> created += uuid }
        bone.eventDispatcher().handleStateRemove { _, uuid -> removed += uuid }
        addLoop(bone, first)
        addLoop(bone, second)

        bone.removeAllPlayerStates()
        bone.removeAllPlayerStates()

        assertTrue(playerStates(bone).isEmpty())
        assertFalse(bone.tick(first.uuid()))
        assertFalse(bone.tick(second.uuid()))
        assertEquals(listOf(first.uuid(), second.uuid()).toSet(), removed.toSet())
        assertEquals(2, removed.size)
        addLoop(bone, first)
        assertTrue(bone.tick(first.uuid()))
        assertEquals(2, created.count { it == first.uuid() })
    }

    @Test
    fun `late tick of a removed state cannot delete replacement state`() {
        val bone = bone()
        val player = player()
        val removed = mutableListOf<UUID>()
        bone.eventDispatcher().handleStateRemove { _, uuid -> removed += uuid }
        addLoop(bone, player)
        // Hold the state a worker could have read just before viewer cleanup.
        val oldState = playerStates(bone).getValue(player.uuid())
        assertTrue(bone.stopAnimation({ true }, "test", player))
        bone.removePlayerState(player.uuid())
        addLoop(bone, player)

        // Resume the old worker deterministically without timing-dependent threads.
        oldState.javaClass.getDeclaredMethod("tick").apply { isAccessible = true }.invoke(oldState)

        assertTrue(bone.tick(player.uuid()))
        assertEquals(listOf(player.uuid()), removed)
        bone.removeAllPlayerStates()
        assertEquals(2, removed.size)
    }

    @Test
    fun `naturally completed animation emits removal only once`() {
        val bone = bone()
        val player = player()
        var removals = 0
        bone.eventDispatcher().handleStateRemove { _, _ -> removals++ }
        val animation = animation(AnimationIterator.Type.PLAY_ONCE)
        assertTrue(bone.addAnimation(
            AnimationOverrideState.MATCHED,
            animation,
            AnimationModifier.builder().player(player).build()
        ) {})

        repeat(100) { bone.tick(player.uuid()) }
        bone.removePlayerState(player.uuid())

        assertTrue(playerStates(bone).isEmpty())
        assertEquals(1, removals)
    }

    private fun addLoop(bone: RenderedBone, player: PlatformPlayer?) {
        assertTrue(bone.addAnimation(
            AnimationOverrideState.MATCHED,
            animation(AnimationIterator.Type.LOOP),
            AnimationModifier.builder().player(player).build()
        ) {})
    }

    private fun animation(type: AnimationIterator.Type) = BlueprintAnimation(
        "test", type, 0.1F, false, emptyMap(), null, AnimationProgress.emptyStorage(0.1F)
    )

    private fun pipeline(bone: RenderedBone) = RenderPipeline(
        ModelRenderer("test", ModelRenderer.Type.GENERAL, linkedMapOf(), emptyMap()),
        RenderSource.of(unused<PlatformLocation>()), arrayOf(bone)
    )

    private fun spawn(pipeline: RenderPipeline, player: PlatformPlayer) {
        channels[player.uuid()] = Proxy.newProxyInstance(
            PlayerChannelHandler::class.java.classLoader, arrayOf(PlayerChannelHandler::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "player" -> player
                "uuid" -> player.uuid()
                else -> error("Unexpected channel call: ${method.name}")
            }
        } as PlayerChannelHandler
        assertTrue(pipeline.spawn(player, emptyBundler) {})
    }

    private fun bone(): RenderedBone {
        val blueprint = BlueprintElement.Group(
            UUID.randomUUID(), BoneName(emptySet(), "test", "test"),
            Float3.ZERO, Float3.ZERO, emptyList(), true
        )
        val group = RendererGroup(1F, air, blueprint, linkedMapOf(), null, BoneItemMapper.EMPTY)
        return group.create(BoneRenderContext(
            RenderSource.of(unused<PlatformLocation>()), unused<SkinData>()
        ))
    }

    private fun player(): PlatformPlayer {
        val uuid = UUID.randomUUID()
        return Proxy.newProxyInstance(
            PlatformPlayer::class.java.classLoader, arrayOf(PlatformPlayer::class.java)
        ) { _, method, _ ->
            check(method.name == "uuid") { "Unexpected player call: ${method.name}" }
            uuid
        } as PlatformPlayer
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)
    ) { _, method, _ -> error("Unexpected ${T::class.java.simpleName} call: ${method.name}") } as T

    @Suppress("UNCHECKED_CAST")
    private fun playerStates(bone: RenderedBone): Map<UUID, Any> = RenderedBone::class.java
        .getDeclaredField("perPlayerState").apply { isAccessible = true }.get(bone) as Map<UUID, Any>
}
