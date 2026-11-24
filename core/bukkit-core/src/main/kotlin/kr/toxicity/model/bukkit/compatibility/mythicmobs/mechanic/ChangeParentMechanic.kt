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
import kr.toxicity.model.bukkit.compatibility.mythicmobs.*
import kr.toxicity.model.util.boneName

class ChangeParentMechanic(mlc: MythicLineConfig) : AbstractSkillMechanic(mlc), INoTargetSkill {
    private val model = mlc.modelPlaceholder
    private val parent = mlc.toPlaceholderString(arrayOf("parent", "parentid")) { it?.boneName }
    private val child = mlc.toPlaceholderString(arrayOf("child", "childid")) { it?.boneName }

    override fun cast(p0: SkillMetadata): SkillResult {
        val args = p0.toPlaceholderArgs()
        val tracker = p0.toTracker(model(args)) ?: return SkillResult.CONDITION_FAILED
        val parentBone = parent(args)?.let(tracker::bone) ?: return SkillResult.CONDITION_FAILED
        val childBone = child(args)?.let(tracker::bone) ?: return SkillResult.CONDITION_FAILED
        if (!childBone.changeParent(parentBone)) return SkillResult.CONDITION_FAILED
        tracker.forceUpdate(true)
        return SkillResult.SUCCESS
    }
}
