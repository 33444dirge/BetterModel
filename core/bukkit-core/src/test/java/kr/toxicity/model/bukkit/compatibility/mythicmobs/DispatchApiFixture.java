/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.compatibility.mythicmobs;

import java.util.UUID;

// Test-only contract fixture. This class is outside the MM namespace and production artifacts.
public final class DispatchApiFixture {
    public static int version = 1;
    public static int registrations;
    public static int closures;
    public static Observer observer;

    public static int apiVersion() { return version; }
    public static Registration register(Object owner, Observer value) {
        registrations++;
        observer = value;
        return () -> closures++;
    }
    public interface Registration extends AutoCloseable { @Override void close(); }
    public interface Observer { Observation begin(Context context); }
    public interface Observation {
        Observation NONE = outcome -> {};
        void finish(Outcome outcome);
    }
    public record Context(UUID dispatchId, UUID entityId, Object entity, Object mobType, Object trigger) {}
    public enum Status { COMPLETED, FAILED, SKIPPED }
    public record Outcome(Status status, Throwable error) {}
}
