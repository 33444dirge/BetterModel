/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.api.tracker;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.BetterModelEvaluator;
import kr.toxicity.model.api.BetterModelPlatform;
import kr.toxicity.model.api.animation.keyframe.KeyframeRuntimeContext;
import kr.toxicity.model.api.entity.BaseEntity;
import kr.toxicity.model.api.nms.HitBox;
import kr.toxicity.model.api.platform.PlatformEntity;
import kr.toxicity.model.api.scheduler.ModelTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EntityTrackerRegistryTest {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private final ModelTask task = new ModelTask() {
        @Override public boolean isCancelled() { return false; }
        @Override public void cancel() {}
    };
    private Field platformField;
    private Object previousPlatform;
    private EntityTrackerRegistry registry;
    private boolean rejectTask;
    private boolean failScheduling;

    @BeforeEach
    void initialize() throws ReflectiveOperationException {
        platformField = BetterModel.class.getDeclaredField("instance");
        platformField.setAccessible(true);
        previousPlatform = platformField.get(null);
        var evaluator = proxy(BetterModelEvaluator.class, (method, _) -> switch (method) {
            case "newContext" -> KeyframeRuntimeContext.empty();
            default -> throw new AssertionError(method);
        });
        platformField.set(null, proxy(BetterModelPlatform.class, (method, _) -> switch (method) {
            case "evaluator" -> evaluator;
            default -> throw new AssertionError(method);
        }));
        var entity = proxy(PlatformEntity.class, (method, args) -> {
            assertEquals("task", method);
            if (failScheduling) throw new IllegalStateException("Scheduling failed");
            if (rejectTask) return null;
            tasks.add((Runnable) args[0]);
            return task;
        });
        var base = proxy(BaseEntity.class, (method, _) -> switch (method) {
            case "uuid" -> UUID.randomUUID();
            case "id" -> 1;
            case "platform" -> entity;
            case "updateTo" -> null;
            default -> throw new AssertionError(method);
        });
        var constructor = EntityTrackerRegistry.class.getDeclaredConstructor(BaseEntity.class);
        constructor.setAccessible(true);
        registry = constructor.newInstance(base);
    }

    @AfterEach
    void restorePlatform() throws IllegalAccessException {
        platformField.set(null, previousPlatform);
    }

    @Test
    void coalescesRequestsAndReadsCurrentHitboxesWhenTaskRuns() {
        var removed = new AtomicInteger();
        var current = new AtomicInteger();
        var id = UUID.randomUUID();
        registry.hitBoxCache.put(id, hitbox(removed::incrementAndGet));
        for (var i = 0; i < 100; i++) registry.requestHitBoxSync();
        assertEquals(1, tasks.size());

        registry.hitBoxCache.remove(id);
        registry.hitBoxCache.put(UUID.randomUUID(), hitbox(current::incrementAndGet));
        tasks.remove().run();
        assertEquals(0, removed.get());
        assertEquals(1, current.get());

        registry.requestHitBoxSync();
        assertEquals(1, tasks.size());
        tasks.remove().run();
        assertEquals(2, current.get());
    }

    @Test
    void concurrentRequestsShareOnePendingTask() throws Exception {
        registry.hitBoxCache.put(UUID.randomUUID(), hitbox(() -> {}));
        try (var executor = Executors.newFixedThreadPool(4)) {
            Callable<Void> request = () -> {
                registry.requestHitBoxSync();
                return null;
            };
            for (var result : executor.invokeAll(java.util.Collections.nCopies(100, request))) result.get();
        }
        assertEquals(1, tasks.size());
        tasks.remove().run();
    }

    @Test
    void emptyAndClosedRegistriesDoNotSync() throws ReflectiveOperationException {
        registry.requestHitBoxSync();
        assertTrue(tasks.isEmpty());
        registry.hitBoxCache.put(UUID.randomUUID(), hitbox(() -> fail("Closed registry must not sync")));
        registry.requestHitBoxSync();
        var closed = EntityTrackerRegistry.class.getDeclaredField("closed");
        closed.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) closed.get(registry)).set(true);
        tasks.remove().run();
        registry.requestHitBoxSync();
        assertTrue(tasks.isEmpty());
    }

    @Test
    void retriesAfterRejectedOrFailedScheduling() {
        registry.hitBoxCache.put(UUID.randomUUID(), hitbox(() -> {}));
        rejectTask = true;
        registry.requestHitBoxSync();
        assertTrue(tasks.isEmpty());
        rejectTask = false;
        failScheduling = true;
        assertThrows(IllegalStateException.class, registry::requestHitBoxSync);
        failScheduling = false;
        registry.requestHitBoxSync();
        assertEquals(1, tasks.size());
    }

    @Test
    void failedSyncDoesNotPreventFutureRequests() {
        registry.hitBoxCache.put(UUID.randomUUID(), hitbox(() -> {
            throw new IllegalStateException("Sync failed");
        }));
        registry.requestHitBoxSync();
        assertThrows(IllegalStateException.class, tasks.remove()::run);
        registry.hitBoxCache.clear();
        registry.hitBoxCache.put(UUID.randomUUID(), hitbox(() -> {}));
        registry.requestHitBoxSync();
        assertEquals(1, tasks.size());
        tasks.remove().run();
    }

    private static HitBox hitbox(Runnable sync) {
        return proxy(HitBox.class, (method, _) -> {
            assertEquals("syncPosition", method);
            sync.run();
            return null;
        });
    }

    private static <T> T proxy(Class<T> type, java.util.function.BiFunction<String, Object[], Object> handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (_, method, args) -> handler.apply(method.getName(), args)));
    }
}
