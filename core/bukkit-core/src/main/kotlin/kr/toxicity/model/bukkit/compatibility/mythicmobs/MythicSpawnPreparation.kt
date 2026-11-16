/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.compatibility.mythicmobs

import io.lumine.mythic.api.config.MythicLineConfig
import io.lumine.mythic.api.mobs.MythicMob
import io.lumine.mythic.api.skills.IWaitableSkill
import io.lumine.mythic.api.skills.IMetaSkill
import io.lumine.mythic.api.skills.SkillMetadata
import io.lumine.mythic.bukkit.events.MythicLoadedEvent
import io.lumine.mythic.bukkit.events.MythicMobPreSpawnConfigureEvent
import io.lumine.mythic.bukkit.events.MythicPreReloadEvent
import io.lumine.mythic.bukkit.events.MythicReloadCompleteEvent
import io.lumine.mythic.core.skills.MetaSkill
import io.lumine.mythic.core.mobs.MobType
import io.lumine.mythic.api.skills.SkillTrigger
import io.lumine.mythic.core.skills.SkillMechanic
import io.lumine.mythic.core.skills.SkillTriggers
import io.lumine.mythic.core.skills.mechanics.CustomMechanic
import io.lumine.mythic.core.skills.mechanics.DelaySkill
import io.lumine.mythic.core.skills.mechanics.MetaSkillMechanic
import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.bukkit.BetterModelBukkit
import kr.toxicity.model.api.entity.BaseEntity
import kr.toxicity.model.api.scheduler.ModelTask
import kr.toxicity.model.api.tracker.EntityTrackerRegistry
import kr.toxicity.model.api.tracker.Tracker
import kr.toxicity.model.bukkit.compatibility.mythicmobs.mechanic.AbstractSkillMechanic
import kr.toxicity.model.bukkit.compatibility.mythicmobs.mechanic.ModelMechanic
import kr.toxicity.model.bukkit.util.PLUGIN
import kr.toxicity.model.bukkit.util.registerListener
import kr.toxicity.model.bukkit.util.wrap
import kr.toxicity.model.util.CONFIG
import kr.toxicity.model.util.PLATFORM
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import java.util.UUID
import java.util.Queue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level

/** Observes the end of immediate onSpawn dispatch without executing or changing its skill conditions. */
internal class MythicSpawnPreparation : Listener {
    @Volatile private var accepting = true
    @Volatile private var preparedTypes = ConcurrentHashMap<MythicMob, List<CompletionMechanic>>()
    private val generation = AtomicLong()
    private val pending = ConcurrentHashMap<UUID, PendingSpawn>()
    // MM 5.13 exposes a merged copy from getSkills(SPAWN), and provides no queue mutator.
    private val skillQueuesField = runCatching {
        MobType::class.java.getDeclaredField("skills").apply { isAccessible = true }
    }.onFailure {
        PLUGIN.logger.log(Level.WARNING, "Initial MythicMobs model preparation is unavailable for this skill layout", it)
    }.getOrNull()

    fun start() = registerListener(this)

    @EventHandler
    fun MythicMobPreSpawnConfigureEvent.prepare() {
        if (!accepting || !CONFIG.module().model || entity is Player || !mobType.hasSkills(SkillTriggers.SPAWN) || BetterModel.registryOrNull(entity.uniqueId) != null) return
        val epoch = generation.get()
        val types = preparedTypes
        val markers = types.computeIfAbsent(mobType) { type ->
            val skills = type.getSkills(SkillTriggers.SPAWN)
            val candidate = skills.firstOrNull { it.hasImmediateModel() } ?: return@computeIfAbsent emptyList()
            val queue = type.completionQueue(skills) ?: return@computeIfAbsent emptyList()
            listOf(CompletionMechanic(candidate.config, queue).also { queue.add(it) })
        }
        if (markers.isEmpty()) return
        if (!accepting || epoch != generation.get() || types !== preparedTypes) {
            markers.forEach { it.queue.remove(it) }
            return
        }
        val registry = EntityTrackerRegistry.getOrCreate(BaseEntity.of(entity.wrap()))
        val state = PendingSpawn(entity, registry)
        registry.initialSpawnPreparation(state.ready)
        pending[entity.uniqueId] = state
        state.ready.whenComplete { _, _ ->
            pending.remove(registry.uuid(), state)
            state.timeout?.cancel()
        }
        if (!accepting || epoch != generation.get()) {
            state.complete()
            return
        }
        try {
            // A canceled publication can retire the entity before MM starts its spawn dispatcher.
            if (BetterModelBukkit.IS_PAPER && !entity.scheduler.execute(PLUGIN, {}, { state.complete() }, 1)) {
                state.complete()
                return
            }
            // An exception in MM's root dispatcher can bypass its final mechanic.
            state.timeout = PLATFORM.scheduler().asyncTaskLater(100) {
                if (!state.ready.isDone) {
                    PLUGIN.logger.warning("Initial MythicMobs model dispatch timed out for ${registry.uuid()}")
                    state.completeOnOwner()
                }
            }
            if (state.ready.isDone) state.timeout?.cancel()
        } catch (error: Exception) {
            PLUGIN.logger.log(Level.WARNING, "Unable to observe initial MythicMobs model dispatch for ${registry.uuid()}", error)
            state.complete()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun MythicMob.completionQueue(execution: Queue<SkillMechanic>): Queue<SkillMechanic>? {
        if (this !is MobType) return null
        val queues = skillQueuesField?.get(this) as? Map<SkillTrigger<*>, Queue<SkillMechanic>> ?: return null
        val last = execution.lastOrNull() ?: return null
        // MM can append COMBAT or SPAWN_OR_LOAD. Locate the actual merged tail by identity.
        return arrayOf(SkillTriggers.COMBAT, SkillTriggers.SPAWN_OR_LOAD, SkillTriggers.SPAWN)
            .asSequence().mapNotNull { queues[it] }.firstOrNull { it.lastOrNull() === last }
    }

    private fun SkillMechanic.isImmediate() = arrayOf(
        arrayOf("delay"), arrayOf("repeat"), arrayOf("repeatinterval", "repeati"), arrayOf("targetinterval", "targeti")
    ).all {
        config.getString(it, "0").lowercase() in setOf("0", "0.0", "0t", "0s")
    }

    private fun SkillMechanic.isModel() = this is CustomMechanic && mechanic.orElse(null) is ModelMechanic

    private fun SkillMechanic.hasImmediateModel(): Boolean {
        if (!isImmediate()) return false
        if (isModel()) return true
        if (this !is MetaSkillMechanic) return false
        val name = config.getString(arrayOf("skill", "s"), "")
        if (!name.matches(Regex("[A-Za-z0-9_.:/-]+"))) return false
        val meta = skill as? MetaSkill ?: return false
        if (meta.baseSkill.redirect.isPresent) return false
        val children = meta.baseSkill.mechanics.toList()
        if (children.any {
                !it.isImmediate() || it is IMetaSkill || it is DelaySkill || it is IWaitableSkill ||
                    (it is CustomMechanic && (it.mechanic.orElse(null) is IWaitableSkill || it.mechanic.orElse(null) is IMetaSkill))
            }) return false
        return children.any { it.isModel() }
    }

    // MM always runs this after its immediate root mechanics have submitted their entity work.
    private inner class CompletionMechanic(config: MythicLineConfig, val queue: Queue<SkillMechanic>) : AbstractSkillMechanic(config) {
        override fun getRunAsync() = true
        override fun isUsableFromCaster(data: SkillMetadata) = true
        override fun isUsableFromSkill(data: SkillMetadata) = true
        override fun execute(data: SkillMetadata): Boolean {
            if (data.cause == SkillTriggers.SPAWN) pending[data.caster.entity.uniqueId]?.completeOnOwner()
            return true
        }
    }

    private inner class PendingSpawn(val entity: Entity, val registry: EntityTrackerRegistry) {
        val ready = CompletableFuture<Void>()
        @Volatile var timeout: ModelTask? = null

        fun completeOnOwner() {
            if (ready.isDone) return
            val completion = Runnable { complete() }
            try {
                if (BetterModelBukkit.IS_PAPER) {
                    if (!entity.scheduler.execute(PLUGIN, completion, completion, 1)) complete()
                } else if (entity.wrap().taskLater(1, completion) == null) complete()
            } catch (error: Exception) {
                PLUGIN.logger.log(Level.WARNING, "Unable to finish initial MythicMobs model dispatch for ${registry.uuid()}", error)
                complete()
            }
        }

        fun complete() {
            if (registry.trackers().isEmpty()) registry.close(Tracker.CloseReason.DESPAWN)
            ready.complete(null)
        }
    }

    private fun reset() {
        accepting = false
        generation.incrementAndGet()
        val types = preparedTypes
        preparedTypes = ConcurrentHashMap()
        types.values.flatten().forEach { it.queue.remove(it) }
        pending.values.toList().forEach { it.complete() }
    }

    @EventHandler
    fun MythicPreReloadEvent.resetPreparation() = reset()

    @EventHandler
    fun MythicReloadCompleteEvent.allowPreparation() { accepting = PLUGIN.isEnabled }

    @EventHandler
    fun MythicLoadedEvent.allowPreparation() { accepting = PLUGIN.isEnabled }

    @EventHandler
    fun PluginDisableEvent.releasePreparation() {
        if (plugin === PLUGIN || plugin.name == "MythicMobs") reset()
    }
}
