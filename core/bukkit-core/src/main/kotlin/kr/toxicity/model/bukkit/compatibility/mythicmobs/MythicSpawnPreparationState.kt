/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.compatibility.mythicmobs

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Identity and completion bookkeeping, independent of live entity access. */
internal class MythicSpawnPreparationState(val entity: Any, val mobType: Any, val epoch: Long) {
    val ready = CompletableFuture<Void>()
    private val requested = AtomicBoolean()
    private val finished = AtomicBoolean()
    private val cancelTimeout = AtomicReference<(() -> Unit)?>()

    init {
        ready.whenComplete { _, _ ->
            finished.set(true)
            cancelTimeout.getAndSet(null)?.invoke()
        }
    }

    fun matches(entity: Any, mobType: Any, epoch: Long) =
        this.entity === entity && this.mobType === mobType && this.epoch == epoch

    fun observe(
        entity: Any, mobType: Any, epoch: Long, isSpawn: Boolean,
        isCurrent: () -> Boolean, onFailure: () -> Unit
    ): (() -> Unit)? {
        if (!isSpawn || !matches(entity, mobType, epoch) || ready.isDone || !isCurrent()) return null
        return MythicDispatchObservation.PreparationObservation(ready) {
            if (!ready.isDone && isCurrent()) onFailure()
        }
    }

    fun requestCompletion() = !ready.isDone && requested.compareAndSet(false, true)

    fun timeout(cancel: () -> Unit) {
        check(cancelTimeout.compareAndSet(null, cancel)) { "Initial preparation timeout already installed" }
        if (ready.isDone) cancelTimeout.getAndSet(null)?.invoke()
    }

    fun complete(cleanup: () -> Unit) {
        if (!finished.compareAndSet(false, true)) return
        try {
            cleanup()
        } finally {
            ready.complete(null)
        }
    }
}
