/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.nms.v1_21_R5

import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.EntityLookup
import com.mojang.authlib.GameProfile
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.bone.RenderedBone
import kr.toxicity.model.api.bukkit.BetterModelBukkit
import kr.toxicity.model.api.bukkit.entity.BaseBukkitEntity
import kr.toxicity.model.api.data.blueprint.ModelBoundingBox
import kr.toxicity.model.api.entity.BaseEntity
import kr.toxicity.model.api.entity.BasePlayer
import kr.toxicity.model.api.mount.MountController
import kr.toxicity.model.api.nms.*
import kr.toxicity.model.api.platform.PlatformEntity
import kr.toxicity.model.api.platform.PlatformItemStack
import kr.toxicity.model.api.platform.PlatformLocation
import kr.toxicity.model.api.platform.PlatformPlayer
import kr.toxicity.model.api.player.PlayerSkinParts
import kr.toxicity.model.api.profile.ModelProfile
import kr.toxicity.model.api.tracker.EntityTrackerRegistry
import kr.toxicity.model.api.tracker.TrackerUpdateAction
import kr.toxicity.model.api.util.TransformedItemStack
import net.kyori.adventure.key.Keyed
import net.minecraft.core.component.DataComponents
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.*
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.network.ServerCommonPacketListenerImpl
import net.minecraft.util.ARGB
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Display.ItemDisplay
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomModelData
import net.minecraft.world.level.entity.LevelEntityGetter
import net.minecraft.world.level.entity.LevelEntityGetterAdapter
import net.minecraft.world.level.entity.PersistentEntitySectionManager
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.entity.CraftEntity
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.plugin.Plugin
import org.joml.Vector3d
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import java.util.function.Consumer
import java.util.function.IntConsumer

class NMSImpl : NMS {

    companion object {
        private const val INJECT_NAME = "bettermodel_channel_handler"

        //Spigot
        private val getGameProfile: (Player) -> GameProfile = createAdaptedFieldGetter { it.gameProfile }
        private val getConnection: (ServerCommonPacketListenerImpl) -> Connection = createAdaptedFieldGetter { it.connection }
        private val spigotChunkAccess = ServerLevel::class.java.fields.firstOrNull {
            it.type == PersistentEntitySectionManager::class.java
        }?.apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        private val ServerLevel.levelGetter
            get(): LevelEntityGetter<Entity> {
                return if (BetterModelBukkit.IS_PAPER) {
                    `moonrise$getEntityLookup`()
                } else {
                    spigotChunkAccess?.get(this)?.let {
                        (it as PersistentEntitySectionManager<*>).entityGetter as LevelEntityGetter<Entity>
                    } ?: throw RuntimeException("LevelEntityGetter")
                }
            }
        private val getEntityById: (LevelEntityGetter<Entity>, Int) -> Entity? = if (BetterModelBukkit.IS_PAPER) { g, i ->
            (g as EntityLookup)[i]
        } else LevelEntityGetterAdapter::class.java.declaredFields.first {
            net.minecraft.world.level.entity.EntityLookup::class.java.isAssignableFrom(it.type)
        }.let {
            it.isAccessible = true
            { e, i ->
                (it[e] as net.minecraft.world.level.entity.EntityLookup<*>).getEntity(i) as? Entity
            }
        }
        private fun Int.toEntity(level: ServerLevel) = getEntityById(level.levelGetter, this)
        //Spigot
        private val hitBoxData by lazy {
            ItemDisplay(EntityType.ITEM_DISPLAY, MinecraftServer.getServer().overworld()).run {
                entityData[Display.DATA_POS_ROT_INTERPOLATION_DURATION_ID] = 3
                entityData.nonDefaultValues!!
            }
        }
    }

    override fun hide(channel: PlayerChannelHandler, registry: EntityTrackerRegistry) {
        val target = registry.entity().handle() as? Entity ?: return
        val list = bundlerOf()
        target.entityData.pack(
            valueFilter = { it.id == SHARED_FLAG }
        )?.let {
            list += ClientboundSetEntityDataPacket(target.id, it).toRegistryDataPacket(channel.uuid(), registry)
        }
        if (target is LivingEntity) {
            val packet = if (registry.hideOption(channel.uuid()).equipment) target.toEmptyEquipmentPacket() else target.toEquipmentPacket()
            packet?.let { list += it }
        }
        list.send(channel.player())
    }

    private fun ClientboundSetEntityDataPacket.toRegistryDataPacket(uuid: UUID, registry: EntityTrackerRegistry) = ClientboundSetEntityDataPacket(id, packedItems().map {
        if (it.id == SHARED_FLAG) SynchedEntityData.DataValue(
            it.id,
            EntityDataSerializers.BYTE,
            registry.entityFlag(uuid, it.value() as Byte)
        ) else it
    })

    inner class PlayerChannelHandlerImpl(
        private val player: CraftPlayer
    ) : PlayerChannelHandler, ChannelDuplexHandler() {
        private val connection = player.handle.connection
        private val uuid = player.uniqueId
        private val base = adapt(player.wrap())
        private var pendingWrite: CompletableFuture<Void>? = null
        private var handlerClosed = false
        private val pendingRemovals = HashMap<Int, CompletableFuture<Void>>()
        private val pendingSpawns = HashSet<CompletableFuture<Void>>()

        init {
            val pipeline = getConnection(connection).channel.pipeline()
            pipeline.addBefore(pipeline.first { it.value is Connection }.key, INJECT_NAME, this)
        }

        override fun close() {
            val channel = getConnection(connection).channel
            channel.eventLoop().submit {
                releasePendingSpawns()
                channel.pipeline().remove(INJECT_NAME)
            }
        }

        override fun base(): BasePlayer = base
        override fun isModEnabled(): Boolean = (if (BetterModelBukkit.IS_PAPER) player.channels() else player.listeningPluginChannels).contains(ModAnimationBundlerImpl.KEY)

        private val playerModel get() = connection.player.id.toRegistry()

        private fun Int.toPlayerEntity() = toEntity(connection.player.level())
        private fun Entity.toRegistry() = BetterModel.registryOrNull(uuid)
        private inline fun Int.toRegistry(
            ifHitBox: (Entity) -> Unit = {}
        ) = (EntityTrackerRegistry.registry(this) ?: toPlayerEntity()?.let {
            if (it is HitBox) ifHitBox(it)
            it.toRegistry()
        })?.takeIf {
            it.isSpawned(player.uniqueId)
        }

        override fun sendEntityData(registry: EntityTrackerRegistry) {
            val handle = registry.entity().handle() as? Entity ?: return
            val list = bundlerOf(
                ClientboundSetPassengersPacket(handle)
            )
            handle.entityData.pack(
                valueFilter = { it.id == SHARED_FLAG }
            )?.let {
                list += ClientboundSetEntityDataPacket(handle.id, it)
            }
            if (handle is LivingEntity) handle.toEquipmentPacket()?.let {
                list += it
            }
            list.send(player.wrap())
        }

        private fun <T : ClientGamePacketListener> Packet<in T>.handle(initialFlags: Map<Int, Byte> = emptyMap()): Packet<in T>? {
            when (this) {
                is ClientboundBundlePacket -> {
                    if (subPackets() is Keyed) return this
                    val flags = subPackets().filterIsInstance<ClientboundSetEntityDataPacket>().mapNotNull { packet ->
                        packet.packedItems().firstOrNull { it.id == SHARED_FLAG }?.let { packet.id to it.value() as Byte }
                    }.toMap()
                    return ClientboundBundlePacket(subPackets().flatMap {
                        when (val handled = it.handle(flags)) {
                            is ClientboundBundlePacket -> handled.subPackets().toList()
                            null -> emptyList()
                            else -> listOf(handled)
                        }
                    })
                }
                is ClientboundAddEntityPacket -> {
                    EntityTrackerRegistry.registry(id)?.takeUnless { it.entity() is BasePlayer }?.let { registry ->
                        if (!registry.isSpawned(this@PlayerChannelHandlerImpl.uuid)) return this
                        // Initial metadata omits default flags, so explicitly hide within the pairing bundle.
                        return ClientboundBundlePacket(listOf(this, ClientboundSetEntityDataPacket(id, listOf(
                            SynchedEntityData.DataValue(SHARED_FLAG, EntityDataSerializers.BYTE,
                                registry.entityFlag(this@PlayerChannelHandlerImpl.uuid, initialFlags[id] ?: 0))
                        ))))
                    }
                    val entity = id.toPlayerEntity() ?: return this
                    if (entity is HitBox) return entity.toFakeAddPacket()
                    val wrap = entity.bukkitEntity.wrap()
                    BetterModel.registry(wrap).ifPresent {
                        wrap.taskLater(1) {
                            it.spawn(player.wrap())
                        }
                    }
                }
                is ClientboundRemoveEntitiesPacket -> {
                    entityIds
                        .asSequence()
                        .mapNotNull map@ {
                            EntityTrackerRegistry.registry(it) ?: it.toRegistry {
                                return@map null
                            }
                        }
                        .forEach {
                            it.remove()
                        }
                }
                is ClientboundSetPassengersPacket -> {
                    vehicle.toRegistry()?.let {
                        return it.mountPacket(it.entity().handle() as? Entity ?: return this, array = passengers)
                    }
                }
                is ClientboundUpdateAttributesPacket if entityId.toPlayerEntity() is HitBox -> return null
                is ClientboundSetEntityDataPacket -> id.toRegistry {
                    return ClientboundSetEntityDataPacket(id, hitBoxData)
                }?.let { registry ->
                    return toRegistryDataPacket(uuid, registry)
                }
                is ClientboundSetEquipmentPacket -> entity.toRegistry {
                    return null
                }?.let {
                    if (it.hideOption(uuid).equipment()) (it.entity().handle() as? LivingEntity)?.toEmptyEquipmentPacket()?.let { packet ->
                        return packet
                    }
                }
                is ClientboundRespawnPacket -> playerModel?.let {
                    bundlerOf(it.mountPacket(connection.player)).send(player.wrap())
                }
                is ClientboundContainerSetSlotPacket if isEquipment(connection.player) && playerModel?.hideOption(uuid)?.equipment() == true -> {
                    return ClientboundContainerSetSlotPacket(containerId, stateId, slot, EMPTY_ITEM)
                }
                is ClientboundContainerSetContentPacket if containerId == 0 && playerModel?.hideOption(uuid)?.equipment() == true -> {
                    return ClientboundContainerSetContentPacket(
                        containerId,
                        stateId,
                        items.apply {
                            PLAYER_EQUIPMENT_SLOT.forEach(IntConsumer { set(it, EMPTY_ITEM) })
                            set(connection.player.hotbarSlot, EMPTY_ITEM)
                        },
                        carriedItem
                    )
                }
            }
            return this
        }

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            val registries = (msg as? Packet<*>)?.spawnRegistries()?.distinct()?.toList().orEmpty()
            if (pendingWrite == null && registries.isEmpty()) {
                writePacket(ctx, msg, promise)
                return
            }
            // Start every entity task immediately, including while earlier pairing packets are pending.
            val ready = CompletableFuture.allOf(*registries.map { registry ->
                pendingRemovals[registry.entity().id()]?.handle { _, _ -> null }?.thenComposeAsync({ prepareSpawn(ctx, registry) }, ctx.executor())
                    ?: prepareSpawn(ctx, registry)
            }.toTypedArray())
            val previous = pendingWrite ?: CompletableFuture.completedFuture(null)
            val next = previous.handle { _, _ -> null }.thenCombine(ready) { _, _ -> Unit }.thenAcceptAsync({
                writePacket(ctx, msg, promise)
            }, ctx.executor())
            pendingWrite = next
            val removedIds = (msg as? Packet<*>)?.removedEntityIds()?.toList().orEmpty()
            removedIds.forEach { pendingRemovals[it] = next }
            next.whenComplete { _, error ->
                removedIds.forEach { if (pendingRemovals[it] === next) pendingRemovals.remove(it) }
                if (error != null) promise.tryFailure(error)
                if (pendingWrite === next) {
                    pendingWrite = null
                    ctx.flush()
                }
            }
        }

        private fun Packet<*>.spawnRegistries(): Sequence<EntityTrackerRegistry> = when (this) {
            is ClientboundBundlePacket -> if (subPackets() is Keyed) emptySequence() else subPackets().asSequence().flatMap { it.spawnRegistries() }
            is ClientboundAddEntityPacket -> sequenceOf(EntityTrackerRegistry.registry(id)).filterNotNull().filter { it.entity() !is BasePlayer }
            else -> emptySequence()
        }

        private fun Packet<*>.removedEntityIds(): Sequence<Int> = when (this) {
            is ClientboundBundlePacket -> if (subPackets() is Keyed) emptySequence() else subPackets().asSequence().flatMap { it.removedEntityIds() }
            is ClientboundRemoveEntitiesPacket -> entityIds.asSequence()
            else -> emptySequence()
        }

        private fun prepareSpawn(ctx: ChannelHandlerContext, registry: EntityTrackerRegistry): CompletableFuture<Void> {
            if (handlerClosed || !ctx.channel().isActive) return CompletableFuture.completedFuture(null)
            val ready = CompletableFuture<Void>()
            pendingSpawns += ready
            ready.whenComplete { _, _ -> ctx.executor().execute { pendingSpawns.remove(ready) } }
            val action = Runnable {
                try {
                    if (!ready.isDone && ctx.channel().isActive && !registry.isClosed) registry.spawn(player.wrap())
                } catch (error: Exception) {
                    (BetterModelBukkit.platform() as Plugin).logger.log(Level.WARNING, "Unable to prepare model pairing for ${registry.uuid()}", error)
                } finally {
                    ready.complete(null)
                }
            }
            try {
                val accepted = if (BetterModelBukkit.IS_PAPER) {
                    (registry.entity().handle() as Entity).bukkitEntity.scheduler.execute(
                        BetterModelBukkit.platform() as Plugin, action, { ready.complete(null) }, 1
                    )
                } else registry.entity().platform().taskLater(1, action) != null
                if (!accepted) ready.complete(null)
            } catch (error: Exception) {
                (BetterModelBukkit.platform() as Plugin).logger.log(Level.WARNING, "Unable to schedule model pairing for ${registry.uuid()}", error)
                ready.complete(null)
            }
            return ready
        }

        private fun writePacket(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            val packet = if (msg is Packet<*>) msg.handle() else msg
            if (packet == null) promise.trySuccess() else super.write(ctx, packet, promise)
        }

        private fun releasePendingSpawns() {
            handlerClosed = true
            pendingRemovals.clear()
            pendingSpawns.toList().forEach { it.complete(null) }
            pendingSpawns.clear()
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            releasePendingSpawns()
            super.channelInactive(ctx)
        }

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            fun EntityTrackerRegistry.updatePlayerLimb() = BetterModel.platform().scheduler().asyncTaskLater(1) {
                if (isClosed) return@asyncTaskLater
                player.handle.containerMenu.sendAllDataToRemote()
                trackers().forEach { tracker ->
                    tracker.update(TrackerUpdateAction.itemMapping()) { bone ->
                        !bone.itemMapper.fixed()
                    }
                }
            }
            when (msg) {
                is ServerboundSetCarriedItemPacket -> {
                    playerModel?.let { registry ->
                        if (!registry.hideOption(uuid).equipment()) return super.channelRead(ctx, msg)
                        if (CONFIG.cancelPlayerModelInventory()) {
                            connection.send(ClientboundSetHeldSlotPacket(player.inventory.heldItemSlot))
                            return
                        }
                        registry.updatePlayerLimb()
                    }
                }
                is ServerboundPlayerActionPacket -> {
                    playerModel?.let { registry ->
                        if (!registry.hideOption(uuid).equipment()) return super.channelRead(ctx, msg)
                        if (CONFIG.cancelPlayerModelInventory()) return
                        registry.updatePlayerLimb()
                    }
                }
            }
            super.channelRead(ctx, msg)
        }

        private fun EntityTrackerRegistry.remove() {
            remove(player.wrap(), false)
        }
    }

    override fun mount(registry: EntityTrackerRegistry, bundler: PacketBundler) {
        val entity = registry.entity().handle()
        if (entity is Entity) bundler += registry.mountPacket(entity)
    }

    private fun EntityTrackerRegistry.mountPacket(entity: Entity, array: IntArray = entity.passengers.filter {
        EntityTrackerRegistry.registry(it.uuid) == null
    }.map {
        it.id
    }.toIntArray()): ClientboundSetPassengersPacket {
        return useByteBuf { buffer ->
            buffer.writeVarInt(entity.id)
            buffer.writeVarIntArray(displays()
                .mapToInt {
                    (it as ModelDisplayImpl).display.id
                }.toArray() + array)
            ClientboundSetPassengersPacket.STREAM_CODEC.decode(buffer)
        }
    }

    override fun inject(player: PlatformPlayer): PlayerChannelHandlerImpl = PlayerChannelHandlerImpl(player.unwarp() as CraftPlayer)

    override fun createBundler(initialCapacity: Int): PacketBundler = bundlerOf(initialCapacity)
    override fun createParallelBundler(threshold: Int): PacketBundler = parallelBundlerOf(threshold)
    override fun createModAnimationBuilder(initialCapacity: Int): ModAnimationBundler = ModAnimationBundlerImpl(initialCapacity)

    override fun create(location: PlatformLocation, yOffset: Double, initialConsumer: Consumer<ModelDisplay>): ModelDisplay = ModelDisplayImpl(
        Vector3d(location.x(), location.y(), location.z()),
        ItemDisplay(EntityType.ITEM_DISPLAY, (location.world().unwarp() as CraftWorld).handle).apply {
            entityData[Display.DATA_POS_ROT_INTERPOLATION_DURATION_ID] = 3
            billboardConstraints = Display.BillboardConstraints.FIXED
            valid = true
            yRot = location.yaw()
            itemTransform = ItemDisplayContext.FIXED
        },
        yOffset
    ).apply {
        initialConsumer.accept(this)
        display.entityData.packDirty()
    }

    override fun createNametag(bone: RenderedBone): ModelNametag = ModelNametagImpl(bone)

    override fun tint(itemStack: PlatformItemStack, rgb: Int): PlatformItemStack {
        return itemStack.unwarp().asVanilla().apply {
            set(DataComponents.CUSTOM_MODEL_DATA, get(DataComponents.CUSTOM_MODEL_DATA)?.let {
                CustomModelData(it.floats, it.flags, it.strings, it.colors
                    .run {
                        if (rgb == 0xFFFFFF) this else map { color ->
                            ARGB.multiply(color, rgb) and 0xFFFFFF
                        }
                    }
                    .ifEmpty { listOf(rgb) })
            } ?: CustomModelData(emptyList(), emptyList(), emptyList(), listOf(rgb)))
        }.asBukkit().wrap()
    }

    override fun createHitBox(entity: BaseEntity, bone: RenderedBone, boundingBox: ModelBoundingBox, mountController: MountController, listener: HitBoxListener): HitBox? {
        val handle = entity.handle() as? Entity ?: return null
        return HitBoxImpl(
            boundingBox.center(),
            bone,
            listener,
            handle,
            mountController
        ).craftEntity
    }
    override fun version(): NMSVersion = NMSVersion.V1_21_R5

    override fun adapt(entity: PlatformEntity): BaseBukkitEntity {
        val craft = entity.unwarp() as CraftEntity
        return BaseEntityImpl(craft)
    }

    override fun adapt(player: PlatformPlayer): BasePlayer {
        val craft = player.unwarp() as CraftPlayer
        return BasePlayerImpl(
            craft,
            dirtyChecked(
                { getGameProfile(craft.handle) },
                { ModelGameProfile(it) },
                { a, b -> a == b && a.properties["texture"] === b.properties["texture"]}
            ),
            dirtyChecked({ craft.handle.toCustomisation() }, { PlayerSkinParts(it) })
        )
    }

    override fun profile(player: PlatformPlayer): ModelProfile = ModelGameProfile(getGameProfile((player.unwarp() as CraftPlayer).handle))

    override fun createSkinItem(model: String, floats: List<Float>, flags: List<Boolean>, strings: List<String>, colors: List<Int>): TransformedItemStack {
        return VanillaItemStack(Items.PLAYER_HEAD).run {
            set(DataComponents.CUSTOM_MODEL_DATA, CustomModelData(floats, flags, strings, colors))
            set(DataComponents.ITEM_MODEL, ResourceLocation.parse(model))
            TransformedItemStack.of(asBukkit().wrap())
        }
    }

    override fun isProxyOnlineMode(): Boolean = ONLINE_MODE
}
