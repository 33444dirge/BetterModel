/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.compatibility.mythicmobs.mechanic

import io.lumine.mythic.api.config.MythicLineConfig
import io.lumine.mythic.api.skills.INoTargetSkill
import io.lumine.mythic.api.skills.SkillMetadata
import io.lumine.mythic.api.skills.SkillResult
import kr.toxicity.model.bukkit.compatibility.mythicmobs.modelPlaceholder
import kr.toxicity.model.bukkit.compatibility.mythicmobs.toNullablePlaceholderBoolean
import kr.toxicity.model.bukkit.compatibility.mythicmobs.toPlaceholderArgs
import kr.toxicity.model.bukkit.compatibility.mythicmobs.toTracker
import kr.toxicity.model.util.toComponent
import kr.toxicity.model.util.warn

/** Compatibility for disabling ModelEngine culling on BetterModel effects. */
internal class CullConfigMechanic(mlc: MythicLineConfig) : AbstractSkillMechanic(mlc), INoTargetSkill {

    private val model = mlc.modelPlaceholder
    private val visual = mlc.toNullablePlaceholderBoolean(arrayOf("viewcull", "vc"))
    private val back = mlc.toNullablePlaceholderBoolean(arrayOf("backcull", "bkc"))
    private val block = mlc.toNullablePlaceholderBoolean(arrayOf("blockcull", "blc"))

    override fun cast(p0: SkillMetadata): SkillResult {
        val args = p0.toPlaceholderArgs()
        // BetterModel has neither back nor block culling, so false already matches its behavior.
        if (back(args) == true || block(args) == true) {
            warn("cullconfig: BetterModel cannot enable backcull or blockcull.".toComponent())
            return SkillResult.CONDITION_FAILED
        }
        val tracker = p0.toTracker(model(args)) ?: return SkillResult.CONDITION_FAILED
        visual(args)?.let { tracker.visualCulling(it) }
        tracker.forceUpdate(true)
        return SkillResult.SUCCESS
    }
}
