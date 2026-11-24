/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import com.github.benmanes.caffeine.cache.Cache
import kr.toxicity.model.BetterModelPlatformImpl
import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.BetterModelConfig
import kr.toxicity.model.api.BetterModelEventBus
import kr.toxicity.model.api.manager.PlayerManager
import kr.toxicity.model.api.nms.NMS
import kr.toxicity.model.api.platform.PlatformAdapter
import kr.toxicity.model.api.platform.PlatformPlayer
import kr.toxicity.model.api.profile.*
import kr.toxicity.model.api.skin.SkinData
import sun.misc.Unsafe
import java.lang.reflect.Proxy
import java.net.URI
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class SkinManagerCacheTest {
    private val platformField = BetterModel::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var previousPlatform: Any? = null
    private val info = ModelProfileInfo(UUID.fromString("00000000-0000-0000-0000-000000000001"), "test")
    private val old = profile("old")
    private val fresh = profile("new")
    private lateinit var cache: Cache<Any, SkinData>
    private var removalResult = BetterModelEventBus.Result.SUCCESS
    private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
        isAccessible = true
        get(null) as Unsafe
    }

    @BeforeTest
    fun initializePlatform() {
        previousPlatform = platformField.get(null)
        val config = stub<BetterModelConfig> { if (it == "namespace") "test" else error(it) }
        val player = stub<PlatformPlayer> { if (it == "uuid") info.id else error(it) }
        val adapter = stub<PlatformAdapter> { if (it == "player") player else error(it) }
        val players = stub<PlayerManager> { if (it == "player") null else error(it) }
        val nms = stub<NMS> { if (it == "profile") fresh else error(it) }
        val events = stub<BetterModelEventBus> { if (it == "call") removalResult else error(it) }
        BetterModel.register(stub<BetterModelPlatformImpl> {
            when (it) {
                "config" -> config
                "adapter" -> adapter
                "manager" -> players
                "nms" -> nms
                "eventBus" -> events
                else -> error(it)
            }
        })
        @Suppress("UNCHECKED_CAST")
        cache = SkinManagerImpl::class.java.getDeclaredField("profileCache").run {
            isAccessible = true
            get(null) as Cache<Any, SkinData>
        }
        cache.invalidateAll()
        removalResult = BetterModelEventBus.Result.SUCCESS
    }

    @AfterTest
    fun restorePlatform() {
        cache.invalidateAll()
        platformField.set(null, previousPlatform)
    }

    @Test
    fun `different textures of one UUID retain their own cached skin`() {
        val first = seed(old)
        val second = seed(fresh)
        assertSame(first, SkinManagerImpl.complete(old.asUncompleted()).join())
        assertSame(second, SkinManagerImpl.complete(fresh.asUncompleted()).join())
    }

    @Test
    fun `cache lookup first resolves the supplied profile`() {
        val cached = seed(old)
        var calls = 0
        val pending = object : ModelProfile.Uncompleted {
            override fun info() = info
            override fun complete(): CompletableFuture<ModelProfile> {
                calls++
                return CompletableFuture.completedFuture(old)
            }
        }
        assertSame(cached, SkinManagerImpl.complete(pending).join())
        assertEquals(1, calls)
    }

    @Test
    fun `cape and arm type distinguish cached skins`() {
        val profiles = listOf(old, profile("old", cape = "cape"), profile("old", slim = true))
        val skins = profiles.map { seed(it) }
        profiles.forEachIndexed { index, profile ->
            assertSame(skins[index], SkinManagerImpl.complete(profile.asUncompleted()).join())
        }
    }

    @Test
    fun `texture metadata alone reuses generated skin data`() {
        val cached = seed(old)
        val metadata = ModelProfile.of(info, old.skin().run { ModelProfileSkin(skin, cape, slim, "updated timestamp") })
        assertSame(cached, SkinManagerImpl.complete(metadata.asUncompleted()).join())
    }

    @Test
    fun `invalidation clears all texture variants for only the requested UUID`() {
        seed(old)
        seed(fresh)
        val other = seed(ModelProfile.of(ModelProfileInfo(UUID.randomUUID(), "other"), old.skin()))
        SkinManagerImpl.removeCache(old)
        assertEquals(listOf(other), cache.asMap().values.toList())
    }

    @Test
    fun `online identity alone cannot keep an outdated texture alive`() {
        val equals = SkinManagerImpl::class.java.getDeclaredMethod("playerEquals", ModelProfile::class.java)
            .apply { isAccessible = true }
        assertFalse(equals.invoke(SkinManagerImpl, old) as Boolean)
        assertTrue(equals.invoke(SkinManagerImpl, fresh) as Boolean)
    }

    @Test
    fun `cancelled expiration retains its texture variant`() {
        val skin = seed(old)
        val key = cache.asMap().keys.single()
        cache.invalidate(key)
        removalResult = BetterModelEventBus.Result.FAIL
        expire(key, skin)
        assertSame(skin, cache.getIfPresent(key))
    }

    @Test
    fun `expired outdated texture stays evicted for an online player`() {
        val skin = seed(old)
        val key = cache.asMap().keys.single()
        cache.invalidate(key)
        expire(key, skin)
        assertNull(cache.getIfPresent(key))
    }

    @Test
    fun `delayed expiration cannot overwrite a newer matching entry`() {
        val expired = seed(fresh)
        val key = cache.asMap().keys.single()
        val newer = seed(fresh)
        expire(key, expired)
        assertSame(newer, cache.getIfPresent(key))
    }

    private fun expire(key: Any, skin: SkinData) {
        SkinManagerImpl::class.java.declaredMethods.first { it.name == "handleExpiration" }
            .apply { isAccessible = true }.invoke(SkinManagerImpl, key, skin)
    }

    private fun profile(texture: String, cape: String? = null, slim: Boolean = false) = ModelProfile.of(
        info, ModelProfileSkin(URI.create("https://example.invalid/$texture"), cape?.let { URI.create("https://example.invalid/$it") }, slim, texture)
    )

    private fun seed(profile: ModelProfile): SkinData {
        // Only the cached profile is accessed here. Runtime tests construct full skin/render data.
        val type = SkinManagerImpl::class.java.declaredClasses.first { it.simpleName == "SkinDataImpl" }
        val skin = unsafe.allocateInstance(type) as SkinData
        type.getDeclaredField("profile").apply { isAccessible = true }.set(skin, profile)
        val keyType = SkinManagerImpl::class.java.declaredClasses.firstOrNull { it.simpleName == "ProfileCacheKey" }
        val key = keyType?.declaredConstructors?.single()?.run {
            isAccessible = true
            newInstance(profile.info().id, profile.skin().skin, profile.skin().cape, profile.skin().slim)
        } ?: profile.info().id
        cache.put(key, skin)
        return skin
    }

    private inline fun <reified T> stub(crossinline answer: (String) -> Any?): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)
    ) { _, method, _ -> answer(method.name) } as T
}
