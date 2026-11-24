/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import kr.toxicity.model.api.bone.BoneName
import kr.toxicity.model.api.data.Float3
import kr.toxicity.model.api.data.Float4
import kr.toxicity.model.api.data.blueprint.BlueprintElement
import kr.toxicity.model.api.data.blueprint.BlueprintFace
import kr.toxicity.model.api.data.blueprint.BlueprintUV
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ModelManagerImplTest {
    private val parent = BlueprintElement.Group(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        BoneName(emptySet(), "pskin_body", "pskin_body"),
        Float3.ZERO,
        Float3.ZERO,
        emptyList(),
        true
    )

    private fun cube(
        from: Float3 = Float3.ZERO,
        uv: Float4 = Float4.MAX_UV
    ) = BlueprintElement.Cube(
        "arm",
        from,
        Float3(2F, 2F, 2F),
        0F,
        Float3(15F, 0F, 0F),
        Float3.ZERO,
        BlueprintFace(
            BlueprintUV("north", uv, 0, 0),
            BlueprintUV("south", uv, 0, 0),
            BlueprintUV("east", uv, 0, 0),
            BlueprintUV("west", uv, 0, 0),
            BlueprintUV("up", uv, 0, 0),
            BlueprintUV("down", uv, 0, 0)
        ),
        null,
        true
    )

    @Test
    fun `stable player skin id is repeatable`() {
        val ids = (0 until 100)
            .map { ModelManagerImpl.stablePlayerSkinId("player_plushie", parent, 0, cube()) }
            .toSet()

        assertEquals(1, ids.size)
    }

    @Test
    fun `stable player skin id changes with identity inputs`() {
        val original = ModelManagerImpl.stablePlayerSkinId("player_plushie", parent, 0, cube())

        assertNotEquals(original, ModelManagerImpl.stablePlayerSkinId("other_model", parent, 0, cube()))
        assertNotEquals(original, ModelManagerImpl.stablePlayerSkinId("player_plushie", parent, 1, cube()))
        assertNotEquals(original, ModelManagerImpl.stablePlayerSkinId("player_plushie", parent, 0, cube(Float3(1F, 0F, 0F))))
        assertNotEquals(original, ModelManagerImpl.stablePlayerSkinId("player_plushie", parent, 0, cube(uv = Float4(1F, 1F, 16F, 16F))))
    }
}
