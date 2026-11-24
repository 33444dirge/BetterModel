/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.compatibility.mythicmobs.targeter


import io.lumine.mythic.api.adapters.AbstractLocation
import io.lumine.mythic.api.config.MythicLineConfig
import io.lumine.mythic.api.skills.SkillMetadata
import io.lumine.mythic.api.skills.targeters.ILocationTargeter
import kr.toxicity.model.bukkit.compatibility.mythicmobs.*
import kr.toxicity.model.util.boneName

class ModelPartTargeter(mlc: MythicLineConfig) : ILocationTargeter {

    private val model = mlc.modelPlaceholder
    private val part = mlc.toPlaceholderString(MM_PART_ID) {
        it?.boneName?.name
    }
    private val x = mlc.toPlaceholderFloat(arrayOf("x"))
    private val y = mlc.toPlaceholderFloat(arrayOf("y"))
    private val z = mlc.toPlaceholderFloat(arrayOf("z"))

    override fun getLocations(p0: SkillMetadata): Collection<AbstractLocation> {
        val args = p0.toPlaceholderArgs()
        return p0.toTracker(model(args))?.bone(part(args) ?: return emptyList())?.hitBoxPosition()?.let {
            listOf(p0.caster.entity.location.add(
                it.x.toDouble() + x(args),
                it.y.toDouble() + y(args),
                it.z.toDouble() + z(args)
            ))
        } ?: emptyList()
    }
}
