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
import kr.toxicity.model.api.animation.keyframe.KeyframeRuntimeContext
import kr.toxicity.model.api.config.DebugConfig
import kr.toxicity.model.api.entity.BaseEntity
import kr.toxicity.model.api.manager.PlayerManager
import kr.toxicity.model.api.nms.PlayerChannelHandler
import kr.toxicity.model.api.platform.PlatformAdapter
import kr.toxicity.model.api.platform.PlatformPlayer
import kr.toxicity.model.api.tracker.EntityTrackerRegistry
import kr.toxicity.model.api.tracker.TrackerCloseReason
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class EntityTrackerRegistryRemovalTest {
    private val platformField = BetterModel::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var previousPlatform: Any? = null
    private var restored = 0
    private lateinit var registry: EntityTrackerRegistry
    private lateinit var player: PlatformPlayer

    @BeforeTest
    fun initializeRegistry() {
        previousPlatform = platformField.get(null)
        val playerId = UUID.randomUUID()
        val entityId = UUID.randomUUID()
        player = stub { if (it == "uuid") playerId else error(it) }
        val handler = stub<PlayerChannelHandler> {
            when (it) {
                "uuid" -> playerId
                "player" -> player
                "sendEntityData" -> { restored++; null }
                else -> error(it)
            }
        }
        val manager = stub<PlayerManager> { if (it == "player") handler else error(it) }
        val adapter = stub<PlatformAdapter> { if (it == "isRegionSafe") false else error(it) }
        val config = stub<BetterModelConfig> { if (it == "debug") DebugConfig.DEFAULT else error(it) }
        val evaluator = stub<BetterModelEvaluator> { if (it == "newContext") KeyframeRuntimeContext.empty() else error(it) }
        BetterModel.register(stub<BetterModelPlatform> {
            when (it) {
                "adapter" -> adapter
                "config" -> config
                "manager" -> manager
                "evaluator" -> evaluator
                else -> error(it)
            }
        })
        registry = EntityTrackerRegistry.getOrCreate(stub<BaseEntity> {
            when (it) {
                "uuid" -> entityId
                "id" -> 2147483000
                "updateTo" -> null
                else -> error(it)
            }
        })
        // Register the viewer without constructing a model or a platform runtime.
        assertFalse(registry.spawn(player))
    }

    @AfterTest
    fun restorePlatform() {
        if (::registry.isInitialized) registry.close(TrackerCloseReason.DESPAWN)
        platformField.set(null, previousPlatform)
    }

    @Test
    fun `explicit removal restores the source entity`() {
        assertTrue(registry.remove(player))
        assertEquals(1, restored)
        assertFalse(registry.remove(player))
        assertEquals(1, restored)
    }

    @Test
    fun `pairing removal omits stale restoration packets`() {
        assertTrue(registry.remove(player, false))
        assertEquals(0, restored)
        assertFalse(registry.viewedPlayer().findAny().isPresent)
    }

    @Test
    fun `ordinary registries are ready without initialization`() {
        assertTrue(registry.initialSpawnPreparation().isDone)
    }

    @Test
    fun `closing releases pending initialization`() {
        val preparation = CompletableFuture<Void>()
        registry.initialSpawnPreparation(preparation)
        assertFalse(preparation.isDone)
        registry.close(TrackerCloseReason.DESPAWN)
        assertTrue(preparation.isDone)
    }

    @Test
    fun `despawning an empty pending registry closes and releases it`() {
        val preparation = CompletableFuture<Void>()
        registry.initialSpawnPreparation(preparation)
        registry.despawn()
        assertTrue(preparation.isDone)
        assertTrue(registry.isClosed)
        assertNull(EntityTrackerRegistry.registry(registry.uuid()))
    }

    @Test
    fun `despawning an ordinary empty registry keeps it available`() {
        registry.despawn()
        assertFalse(registry.isClosed)
    }

    @Test
    fun `initialization installed after close is released`() {
        registry.close(TrackerCloseReason.DESPAWN)
        val preparation = CompletableFuture<Void>()
        registry.initialSpawnPreparation(preparation)
        assertTrue(preparation.isDone)
    }

    private inline fun <reified T> stub(crossinline answer: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            answer(method.name)
        } as T
}
