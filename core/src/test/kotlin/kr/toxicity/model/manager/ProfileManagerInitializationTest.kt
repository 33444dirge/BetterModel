/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.BetterModelPlatformImpl
import kr.toxicity.model.api.nms.NMS
import kr.toxicity.model.api.profile.ModelProfile
import kr.toxicity.model.api.profile.ModelProfileSupplier
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertSame

class ProfileManagerInitializationTest {
    @Test
    fun `startup preserves a supplier registered by an earlier compatibility hook`() {
        val platformField = BetterModel::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val supplierField = ProfileManagerImpl::class.java.getDeclaredField("profileSupplier").apply { isAccessible = true }
        val previousPlatform = platformField.get(null)
        val previousSupplier = supplierField.get(null)
        val registered = ModelProfileSupplier { ModelProfile.of(it).asUncompleted() }
        val nms = Proxy.newProxyInstance(NMS::class.java.classLoader, arrayOf(NMS::class.java)) { _, _, _ ->
            error("Startup must preserve the registered supplier without initializing a default supplier")
        } as NMS
        val platform = Proxy.newProxyInstance(
            BetterModelPlatformImpl::class.java.classLoader, arrayOf(BetterModelPlatformImpl::class.java)
        ) { _, method, _ ->
            if (method.name == "nms") nms else error("Unexpected platform access: ${method.name}")
        } as BetterModelPlatformImpl
        try {
            BetterModel.register(platform)
            ProfileManagerImpl.supplier(registered)
            ProfileManagerImpl.start()
            assertSame(registered, ProfileManagerImpl.supplier())
        } finally {
            supplierField.set(null, previousSupplier)
            platformField.set(null, previousPlatform)
        }
    }
}
