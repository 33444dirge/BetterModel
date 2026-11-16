/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bone

import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.BetterModelPlatform
import kr.toxicity.model.api.animation.*
import kr.toxicity.model.api.bone.*
import kr.toxicity.model.api.data.Float3
import kr.toxicity.model.api.data.blueprint.*
import kr.toxicity.model.api.data.renderer.*
import kr.toxicity.model.api.platform.*
import kr.toxicity.model.api.nms.*
import kr.toxicity.model.api.skin.SkinData
import kr.toxicity.model.api.util.MathUtil
import org.joml.Quaternionf
import org.joml.Vector3f
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.test.*

class RenderedBoneTransformTest {
    private val platformField = BetterModel::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var previousPlatform: Any? = null
    private val bundler = AnimationBundler(stub<PacketBundler> { error(it) }, stub<ModAnimationBundler> { error(it) })
    private val context = BoneRenderContext(
        RenderSource.of(stub<PlatformLocation> { error(it) }), stub<SkinData> { error(it) }
    )
    private val air = object : PlatformItemStack {
        override fun isAir() = true
        override fun clone() = this
        override fun enchant(enchant: Boolean) = this
        override fun itemModel(namespace: PlatformNamespace?) = this
    }

    @BeforeTest
    fun initializePlatform() {
        previousPlatform = platformField.get(null)
        val adapter = stub<PlatformAdapter> { if (it == "air") air else error(it) }
        BetterModel.register(stub<BetterModelPlatform> { if (it == "adapter") adapter else error(it) })
    }

    @AfterTest
    fun restorePlatform() = platformField.set(null, previousPlatform)

    @Test
    fun `static descendants follow changed global rotation exactly once`() {
        val root = tree()
        var radians = 0F
        root.flatten().forEach { it.addGlobalRotModifier({ true }) { q -> q.rotateY(radians) } }
        tick(root, 10)
        radians = Math.toRadians(75.0).toFloat()
        tick(root)
        root.flatten().forEach {
            assertRotation(75F, current(it).rotation())
            assertEquals(current(it).position().x, it.worldPosition().x, 0.0001F)
            assertEquals(current(it).position().z, it.worldPosition().z, 0.0001F)
        }
    }

    @Test
    fun `static position and local rotation modifiers refresh after settling`() {
        val root = tree()
        var offset = 0F
        root.addPositionModifier({ true }) { it.add(offset, 0F, 0F) }
        root.addLocalRotModifier({ true }) { it.rotateY(offset) }
        tick(root, 10)
        offset = 1F
        tick(root)
        assertEquals(1F, current(root).position().x, 0.0001F)
        assertRotation(Math.toDegrees(1.0).toFloat(), current(root).rotation())
    }

    @Test
    fun `modifier on a child preserves the current animation sample of its parent`() {
        val root = tree()
        val child = root.flatten().toList()[1]
        child.addGlobalRotModifier({ true }) { it }
        animate(root)
        tick(root, 2)
        tick(root, 10)
        assertEquals(expectedPosition(10), current(root).position().x, 0.001F)
        assertEquals(expectedPosition(10) - 1F, current(child).position().x, 0.001F)
        assertEquals(expectedPosition(10), root.worldPosition().x, 0.001F)
    }

    @Test
    fun `modifier changes do not jump to the end of an animation segment`() {
        val root = tree()
        var yaw = 0F
        root.addGlobalRotModifier({ true }) { it.rotateY(yaw) }
        animate(root)
        tick(root, 2)
        tick(root, 10)
        yaw = 0.5F
        tick(root)
        assertEquals(expectedPosition(9), current(root).position().x, 0.001F)
        assertEquals(expectedPosition(9), root.worldPosition().x, 0.001F)
        assertRotation(Math.toDegrees(0.5).toFloat(), current(root).rotation())
    }

    @Test
    fun `step animation remains immediate while external modifiers keep updating`() {
        val root = tree()
        var yaw = 0F
        root.addGlobalRotModifier({ true }) { it.rotateY(yaw) }
        animate(root, skip = true)
        tick(root, 2)
        assertEquals(10F, current(root).position().x, 0.0001F)
        assertEquals(0, root.interpolationDuration())
        assertEquals(0, root.flatten().toList()[1].interpolationDuration())
        yaw = 0.5F
        tick(root)
        assertEquals(10F, current(root).position().x, 0.0001F)
        assertRotation(Math.toDegrees(0.5).toFloat(), current(root).rotation())
    }

    @Test
    fun `targeted animation samples modifiers independently of the global state`() {
        val root = tree()
        val id = UUID.randomUUID()
        val player = stub<PlatformPlayer> { if (it == "uuid") id else error(it) }
        var yaw = 0F
        root.addGlobalRotModifier({ true }) { it.rotateY(yaw) }
        animate(root, player = player)
        tick(root, 4)
        tick(root, 4, id)
        yaw = 0.5F
        tick(root)
        tick(root, uuid = id)
        assertRotation(Math.toDegrees(0.5).toFloat(), current(root).rotation())
        assertRotation(Math.toDegrees(0.5).toFloat(), current(root, id).rotation())
    }

    @Test
    fun `unmodified animation retains its keyframe interpolation duration`() {
        val root = tree()
        animate(root)
        tick(root, 2)
        assertEquals(10F, current(root).position().x, 0.0001F)
        assertEquals(10, root.interpolationDuration())
        tick(root, 10)
        assertEquals(expectedPosition(10), root.worldPosition().x, 0.001F)
    }

    @Test
    fun `equivalent quaternion endpoints do not introduce a full turn`() {
        val root = tree()
        root.addGlobalRotModifier({ true }) { it }
        animate(root, endYaw = 360F)
        tick(root, 2)
        tick(root, 10)
        assertRotation(0F, current(root).rotation())
    }

    @Test
    fun `local to global rotation transition retains both endpoint spaces`() {
        val root = tree(yaw = 90F)
        val child = root.flatten().toList()[1]
        child.addGlobalRotModifier({ true }) { it }
        val frames = AnimationKeyframe(arrayOf(
            AnimationProgress.empty(0F, false, false), AnimationProgress.empty(0.5F, false, true)
        ))
        val animation = BlueprintAnimation("space", AnimationIterator.Type.HOLD_ON_LAST, 0.5F, false,
            mapOf(child.name() to BlueprintAnimator(child.name(), frames)), null, AnimationProgress.emptyStorage(0.5F))
        assertTrue(child.addAnimation(AnimationOverrideState.MATCHED, animation, AnimationModifier.builder().start(0).build()) {})
        tick(root, 2)
        assertRotation(90F * (1F - expectedPosition(20) / 10F), current(child).rotation())
        tick(root, 10)
        assertRotation(90F * (1F - expectedPosition(10) / 10F), current(child).rotation())
    }

    @Test
    fun `cross root parenting samples ancestors when modifier is registered before parenting`() {
        crossRoot(registerFirst = true)
    }

    @Test
    fun `cross root parenting samples ancestors when modifier is registered after parenting`() {
        crossRoot(registerFirst = false)
    }

    @Test
    fun `modifier on the original root also samples a reparented childs ancestors`() {
        val root = tree()
        val child = root.flatten().toList()[1]
        val parent = tree()
        assertTrue(child.changeParent(parent))
        root.addGlobalRotModifier({ true }) { it }
        animate(parent)
        tick(listOf(root, parent), 12)
        assertEquals(expectedPosition(10), current(parent).position().x, 0.001F)
        assertEquals(expectedPosition(10) - 1F, current(child).position().x, 0.001F)
    }

    private fun crossRoot(registerFirst: Boolean) {
        val root = tree()
        val child = root.flatten().toList()[1]
        val parent = tree()
        if (registerFirst) child.addGlobalRotModifier({ true }) { it }
        assertTrue(child.changeParent(parent))
        if (!registerFirst) child.addGlobalRotModifier({ true }) { it }
        animate(parent)
        tick(listOf(root, parent), 12)
        assertEquals(expectedPosition(10), current(parent).position().x, 0.001F)
        assertEquals(expectedPosition(10) - 1F, current(child).position().x, 0.001F)
    }

    private fun expectedPosition(delay: Int) = 10F * (1F - delay / (40F * (0.5F + MathUtil.FRAME_EPSILON)))

    @Test
    fun `unmodified static bones stop generating updates`() {
        val root = tree()
        tick(root)
        assertTrue(root.flatten().noneMatch { it.tick() })
    }

    private fun tree(yaw: Float = 0F): RenderedBone {
        fun group(name: String, position: Float3, rotation: Float3 = Float3.ZERO) = BlueprintElement.Group(
            UUID.randomUUID(), BoneName(emptySet(), name, name), position, rotation, emptyList(), true
        )
        val child = group("child", Float3(16F, 0F, 0F))
        val root = group("root", Float3.ZERO, Float3(0F, yaw, 0F))
        return RendererGroup(1F, air, root, linkedMapOf(child.name() to RendererGroup(
            1F, air, child, linkedMapOf(), null, BoneItemMapper.EMPTY
        )), null, BoneItemMapper.EMPTY).create(context)
    }

    private fun animate(root: RenderedBone, skip: Boolean = false, player: PlatformPlayer? = null, endYaw: Float = 0F) {
        val frames = AnimationKeyframe.builder(2, false)
        frames.write(0F, Vector3f(), Vector3f(), Vector3f(), skip)
        frames.write(0.5F, Vector3f(10F, 0F, 0F), Vector3f(), Vector3f(0F, endYaw, 0F), skip)
        val animation = BlueprintAnimation("test", AnimationIterator.Type.LOOP, 0.5F, false,
            mapOf(root.name() to BlueprintAnimator(root.name(), frames.build())), null, AnimationProgress.emptyStorage(0.5F))
        root.flatten().forEach {
            assertTrue(it.addAnimation(AnimationOverrideState.MATCHED, animation, AnimationModifier.builder().start(0).player(player).build()) {})
        }
    }

    private fun tick(root: RenderedBone, count: Int = 1, uuid: UUID? = null) {
        tick(listOf(root), count, uuid)
    }

    private fun tick(roots: List<RenderedBone>, count: Int = 1, uuid: UUID? = null) {
        val bones = roots.flatMap { it.flatten().toList() }
        repeat(count) {
            bones.forEach { if (uuid == null) it.tick() else it.tick(uuid) }
            bones.forEach { it.sendTransformation(uuid, bundler) }
        }
    }

    private fun current(bone: RenderedBone, uuid: UUID? = null): BoneMovement {
        val state = if (uuid == null) RenderedBone::class.java.getDeclaredField("globalState").run {
            isAccessible = true
            get(bone)
        } else RenderedBone::class.java.getDeclaredField("perPlayerState").run {
            isAccessible = true
            (get(bone) as Map<*, *>)[uuid]
        }
        return state!!::class.java.getDeclaredField("current").run {
            isAccessible = true
            get(state) as BoneMovement
        }
    }

    private fun assertRotation(degrees: Float, rotation: Quaternionf) {
        val forward = rotation.transform(Vector3f(0F, 0F, 1F))
        val actual = Math.toDegrees(kotlin.math.atan2(forward.x, forward.z).toDouble()).toFloat()
        assertEquals(degrees, actual, 0.001F)
    }

    private inline fun <reified T> stub(crossinline answer: (String) -> Any?): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)
    ) { _, method, _ -> answer(method.name) } as T
}
