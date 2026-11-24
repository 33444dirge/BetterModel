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

class SyncYawMechanic(mlc: MythicLineConfig) : AbstractSkillMechanic(mlc), INoTargetSkill {
    private val model = mlc.modelPlaceholder

    override fun cast(p0: SkillMetadata): SkillResult {
        val args = p0.toPlaceholderArgs()
        val tracker = p0.toTracker(model(args)) ?: return SkillResult.CONDITION_FAILED
        val rotator = tracker.bodyRotator() ?: return SkillResult.CONDITION_FAILED
        rotator.syncYaw()
        tracker.forceUpdate(true)
        return SkillResult.SUCCESS
    }
}
