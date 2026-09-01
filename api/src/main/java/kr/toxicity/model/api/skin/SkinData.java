/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.api.skin;

import kr.toxicity.model.api.armor.PlayerArmor;
import kr.toxicity.model.api.profile.ModelProfile;
import kr.toxicity.model.api.util.TransformedItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Skin data of player.
 */
public interface SkinData {

    /**
     * Gets a dynamically textured custom skin model.
     * <p>
     * The identifier is supplied by BetterModel while importing a group tagged with
     * {@code pskin}. Unknown identifiers return an empty item.
     *
     * @param identifier imported custom skin model identifier
     * @return an item containing this skin applied to the requested model
     * @since 3.2.1
     * @example {@code skinData.customModel("player_plushie_head");}
     */
    default @NotNull TransformedItemStack customModel(@NotNull String identifier) {
        return TransformedItemStack.empty();
    }

    /**
     * Gets model skin
     * @return skin
     */
    @NotNull ModelProfile profile();

    /**
     * Gets head part
     * @param armor armor
     * @return head
     */
    @NotNull TransformedItemStack head(@NotNull PlayerArmor armor);

    /**
     * Gets hip part
     * @param armor armor
     * @return hip
     */
    @NotNull TransformedItemStack hip(@NotNull PlayerArmor armor);

    /**
     * Gets waist part
     * @param armor armor
     * @return waist
     */
    @NotNull TransformedItemStack waist(@NotNull PlayerArmor armor);

    /**
     * Gets chest part
     * @param armor armor
     * @return chest
     */
    @NotNull TransformedItemStack chest(@NotNull PlayerArmor armor);

    /**
     * Gets left arm part
     * @param armor armor
     * @return left arm
     */
    @NotNull TransformedItemStack leftArm(@NotNull PlayerArmor armor);

    /**
     * Gets left forearm part
     * @return left forearm
     */
    @NotNull TransformedItemStack leftForeArm();

    /**
     * Gets right arm part
     * @param armor armor
     * @return right arm
     */
    @NotNull TransformedItemStack rightArm(@NotNull PlayerArmor armor);

    /**
     * Gets right forearm part
     * @return right forearm
     */
    @NotNull TransformedItemStack rightForeArm();

    /**
     * Gets left leg part
     * @param armor armor
     * @return left leg
     */
    @NotNull TransformedItemStack leftLeg(@NotNull PlayerArmor armor);

    /**
     * Gets left foreleg part
     * @param armor armor
     * @return left foreleg
     */
    @NotNull TransformedItemStack leftForeLeg(@NotNull PlayerArmor armor);

    /**
     * Gets right leg part
     * @param armor armor
     * @return right leg
     */
    @NotNull TransformedItemStack rightLeg(@NotNull PlayerArmor armor);

    /**
     * Gets right foreleg part
     * @param armor armor
     * @return right foreleg
     */
    @NotNull TransformedItemStack rightForeLeg(@NotNull PlayerArmor armor);

    /**
     * Gets cape
     * @param armor armor
     * @return cape
     */
    @Nullable TransformedItemStack cape(@NotNull PlayerArmor armor);
}
