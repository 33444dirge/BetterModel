/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.compatibility.mythicmobs

import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Optional v1 contract adapter. No new MM API type is linked into mandatory classes. */
internal object MythicDispatchObservation {
    data class Context(val dispatchId: UUID, val entityId: UUID, val entity: Any, val mobType: Any, val trigger: Any)

    fun subscribe(
        loader: ClassLoader, owner: Any,
        begin: (Context) -> (() -> Unit)?,
        warning: (String, Throwable?) -> Unit,
        apiName: String = "io.lumine.mythic.api.skills.observers.RootSkillDispatchObservers"
    ): AutoCloseable? {
        val api = try {
            Class.forName(apiName, false, loader)
        } catch (_: ClassNotFoundException) {
            return null
        } catch (error: LinkageError) {
            warning("Unable to load optional MythicMobs root-dispatch observation API", error)
            return null
        }
        try {
            val version = api.getMethod("apiVersion").invoke(null)
            if (version != 1) {
                warning("Unsupported MythicMobs root-dispatch observation API version $version", null)
                return null
            }
            val observerType = Class.forName("$apiName\$Observer", false, loader)
            val observationType = Class.forName("$apiName\$Observation", false, loader)
            val contextType = Class.forName("$apiName\$Context", false, loader)
            val outcomeType = Class.forName("$apiName\$Outcome", false, loader)
            val none = observationType.getField("NONE").get(null)
            val dispatchId = contextType.getMethod("dispatchId")
            val entityId = contextType.getMethod("entityId")
            val entity = contextType.getMethod("entity")
            val mobType = contextType.getMethod("mobType")
            val trigger = contextType.getMethod("trigger")
            val status = outcomeType.getMethod("status")
            val error = outcomeType.getMethod("error")
            val register = api.methods.single { it.name == "register" && it.parameterCount == 2 && it.parameterTypes[1] == observerType }
            require(register.parameterTypes[0].isInstance(owner)) { "Root-dispatch registration owner type mismatch" }
            val closed = AtomicBoolean()
            val beginReference = AtomicReference<((Context) -> (() -> Unit)?)?>(begin)
            val pending = ConcurrentHashMap.newKeySet<AtomicReference<(() -> Unit)?>>()
            val observer = Proxy.newProxyInstance(loader, arrayOf(observerType)) { proxy, method, args ->
                when (method.name) {
                    "begin" -> {
                        val value = args!![0]
                        val callback = beginReference.get()?.invoke(Context(
                            dispatchId.invoke(value) as UUID, entityId.invoke(value) as UUID,
                            entity.invoke(value), mobType.invoke(value), trigger.invoke(value)
                        ))
                        if (callback == null || closed.get()) none else {
                            val reference = AtomicReference<(() -> Unit)?>(callback)
                            pending.add(reference)
                            if (closed.get()) {
                                reference.set(null)
                                pending.remove(reference)
                                none
                            } else Proxy.newProxyInstance(loader, arrayOf(observationType)) { observation, finish, values ->
                                when (finish.name) {
                                    "finish" -> {
                                        val action = reference.getAndSet(null)
                                        pending.remove(reference)
                                        if (action != null && !closed.get()) {
                                            val outcome = values!![0]
                                            if ((status.invoke(outcome) as Enum<*>).name == "FAILED") {
                                                if (error.invoke(outcome) != null) action()
                                                else warning("MythicMobs reported FAILED root dispatch without an error", null)
                                            }
                                        }
                                        null
                                    }
                                    "equals" -> observation === values!![0]
                                    "hashCode" -> System.identityHashCode(observation)
                                    "toString" -> "BetterModel root-dispatch observation"
                                    else -> throw UnsupportedOperationException(finish.name)
                                }
                            }
                        }
                    }
                    "equals" -> proxy === args!![0]
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "BetterModel root-dispatch observer"
                    else -> throw UnsupportedOperationException(method.name)
                }
            }
            val registration = register.invoke(null, owner, observer) as AutoCloseable
            return AutoCloseable {
                if (closed.compareAndSet(false, true)) {
                    beginReference.set(null)
                    pending.forEach { it.set(null) }
                    pending.clear()
                    registration.close()
                }
            }
        } catch (error: ReflectiveOperationException) {
            warning("Unable to link optional MythicMobs root-dispatch observation API", error)
        } catch (error: LinkageError) {
            warning("Unable to link optional MythicMobs root-dispatch observation API", error)
        } catch (error: IllegalArgumentException) {
            warning("Incompatible MythicMobs root-dispatch observation API", error)
        } catch (error: NoSuchElementException) {
            warning("Incomplete MythicMobs root-dispatch observation API", error)
        }
        return null
    }
}
