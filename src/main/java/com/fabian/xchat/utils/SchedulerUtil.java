package com.fabian.xchat.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * X-Proyects Unified Scheduler System (canonical implementation).
 * <p>
 * Identical across every X-Proyects plugin — only the package changes.
 * Folia-aware: detects RegionizedServer and routes tasks through
 * AsyncScheduler / RegionScheduler / EntityScheduler via reflection,
 * falling back to the standard Bukkit scheduler on Spigot/Paper.
 */
public final class SchedulerUtil {

    private static boolean isFolia = false;

    private static Object asyncScheduler;
    private static Method asyncRunDelayedMethod;
    private static Method asyncRunTimerMethod;

    private static Object regionScheduler;
    private static Method regionRunTimerMethod;
    private static Method regionRunDelayedMethod;
    private static Method regionRunMethod;

    private static Method entityGetSchedulerMethod;
    private static Method entityRunTimerMethod;
    private static Method entityRunMethod;

    private static Method teleportAsyncMethod;

    static {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            isFolia = true;

            Object server = Bukkit.getServer();

            // AsyncScheduler
            Method getAsyncSchedulerMethod = server.getClass().getMethod("getAsyncScheduler");
            asyncScheduler = getAsyncSchedulerMethod.invoke(server);
            asyncRunDelayedMethod = asyncScheduler.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, long.class, TimeUnit.class);
            asyncRunTimerMethod = asyncScheduler.getClass().getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);

            // RegionScheduler
            Method getRegionSchedulerMethod = server.getClass().getMethod("getRegionScheduler");
            regionScheduler = getRegionSchedulerMethod.invoke(server);
            regionRunTimerMethod = regionScheduler.getClass().getMethod("runAtFixedRate", Plugin.class, Location.class, Consumer.class, long.class, long.class);
            regionRunDelayedMethod = regionScheduler.getClass().getMethod("runDelayed", Plugin.class, Location.class, Consumer.class, long.class);
            regionRunMethod = regionScheduler.getClass().getMethod("execute", Plugin.class, Location.class, Runnable.class);

            // TeleportAsync (Paper/Folia)
            try {
                teleportAsyncMethod = Class.forName("org.bukkit.entity.Entity").getMethod("teleportAsync", Location.class);
            } catch (Exception e) {
                // Not found, will fall back to sync teleport
            }

            // EntityScheduler
            try {
                entityGetSchedulerMethod = Class.forName("org.bukkit.entity.Entity").getMethod("getScheduler");
                Class<?> entitySchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
                entityRunTimerMethod = entitySchedulerClass.getMethod("runAtFixedRate", Plugin.class, Consumer.class, Runnable.class, long.class, long.class);
                entityRunMethod = entitySchedulerClass.getMethod("run", Plugin.class, Consumer.class, Runnable.class);
            } catch (Exception e) {}

        } catch (Exception ignored) {
            isFolia = false;
        }
    }

    private SchedulerUtil() {
        // Static utility class — no instances
    }

    /**
     * Handle to a scheduled task, cancellable regardless of platform.
     */
    public interface TaskWrapper {
        void cancel();
    }

    public static boolean isFolia() {
        return isFolia;
    }

    // ──────────────────────────────────────────────────────
    //  TELEPORT
    // ──────────────────────────────────────────────────────

    public static void teleport(Player player, Location location) {
        teleport(null, player, location, null);
    }

    public static void teleport(Plugin plugin, Player player, Location location, Runnable callback) {
        if (teleportAsyncMethod != null) {
            try {
                Object futureObj = teleportAsyncMethod.invoke(player, location);
                if (futureObj instanceof java.util.concurrent.CompletableFuture) {
                    java.util.concurrent.CompletableFuture<?> future = (java.util.concurrent.CompletableFuture<?>) futureObj;
                    future.thenAccept(result -> {
                        if (callback != null) {
                            if (plugin != null) {
                                runRegion(plugin, location, callback);
                            } else {
                                callback.run();
                            }
                        }
                    });
                    return;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        player.teleport(location);
        if (callback != null) {
            callback.run();
        }
    }

    /**
     * Alias of {@link #teleport(Player, Location)}.
     */
    public static void teleportAsync(Player player, Location location) {
        teleport(player, location);
    }

    // ──────────────────────────────────────────────────────
    //  ASYNC TASKS
    // ──────────────────────────────────────────────────────

    public static void runAsync(Plugin plugin, Runnable task) {
        if (isFolia) {
            // In Folia, Bukkit.getScheduler().runTaskAsynchronously is unsupported.
            // Using CompletableFuture is safe and native for off-thread DB operations.
            java.util.concurrent.CompletableFuture.runAsync(task);
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    /**
     * Alias of {@link #runAsync(Plugin, Runnable)}.
     */
    public static void runTaskAsynchronously(Plugin plugin, Runnable task) {
        runAsync(plugin, task);
    }

    /**
     * Alias of {@link #runAsync(Plugin, Runnable)}.
     */
    public static void runTaskAsync(Plugin plugin, Runnable task) {
        runAsync(plugin, task);
    }

    public static TaskWrapper runAsyncDelayed(Plugin plugin, Runnable task, long delayTicks) {
        if (isFolia) {
            try {
                long delayMillis = delayTicks * 50L;
                Consumer<?> consumer = (scheduledTask) -> task.run();
                Object scheduledTaskObj = asyncRunDelayedMethod.invoke(asyncScheduler, plugin, consumer, delayMillis, TimeUnit.MILLISECONDS);
                return createWrapper(scheduledTaskObj);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks);
            return bukkitTask::cancel;
        }
        return () -> {};
    }

    /**
     * Alias of {@link #runAsyncDelayed(Plugin, Runnable, long)}.
     */
    public static Object runAsyncLater(Plugin plugin, Runnable task, long delayTicks) {
        TaskWrapper wrapper = runAsyncDelayed(plugin, task, delayTicks);
        return wrapper;
    }

    public static TaskWrapper runAsyncTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (isFolia) {
            try {
                long initialDelayMillis = Math.max(1, delayTicks) * 50L;
                long periodMillis = periodTicks * 50L;
                Consumer<?> consumer = (scheduledTask) -> task.run();
                Object scheduledTaskObj = asyncRunTimerMethod.invoke(asyncScheduler, plugin, consumer, initialDelayMillis, periodMillis, TimeUnit.MILLISECONDS);
                return createWrapper(scheduledTaskObj);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks);
            return bukkitTask::cancel;
        }
        return () -> {};
    }

    // ──────────────────────────────────────────────────────
    //  SYNC / REGION TASKS
    // ──────────────────────────────────────────────────────

    /**
     * Runs a task on the main thread (or the global region on Folia).
     */
    public static void runTask(Plugin plugin, Runnable runnable) {
        runRegion(plugin, null, runnable);
    }

    /**
     * Runs a task after a delay, on the main thread (or a region on Folia).
     *
     * @return an opaque task handle (BukkitTask or TaskWrapper) usable with {@link #cancelTask(Object)}
     */
    public static Object runTaskLater(Plugin plugin, Runnable runnable, long delayTicks) {
        TaskWrapper wrapper = runRegionDelayed(plugin, null, runnable, delayTicks);
        return wrapper;
    }

    /**
     * Alias of {@link #runTaskLater(Plugin, Runnable, long)}.
     */
    public static Object runLater(Plugin plugin, Runnable runnable, long ticks) {
        return runTaskLater(plugin, runnable, ticks);
    }

    /**
     * Runs a repeating task on the main thread (or a region on Folia).
     *
     * @return an opaque task handle (BukkitTask or TaskWrapper) usable with {@link #cancelTask(Object)}
     */
    public static Object runTaskTimer(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        TaskWrapper wrapper = runRegionTimer(plugin, null, runnable, delayTicks, periodTicks);
        return wrapper;
    }

    /**
     * Alias of {@link #runTaskTimer(Plugin, Runnable, long, long)}.
     */
    public static Object runTimer(Plugin plugin, Runnable runnable, long delay, long period) {
        return runTaskTimer(plugin, runnable, delay, period);
    }

    /**
     * Cancels a task handle returned by the run* methods of this utility.
     */
    public static void cancelTask(Object task) {
        if (task == null) return;
        if (task instanceof BukkitTask) {
            ((BukkitTask) task).cancel();
        } else if (task instanceof TaskWrapper) {
            ((TaskWrapper) task).cancel();
        } else if (task instanceof org.bukkit.scheduler.BukkitRunnable) {
            ((org.bukkit.scheduler.BukkitRunnable) task).cancel();
        } else {
            // Folia ScheduledTask via reflection
            try {
                Method cancelMethod = task.getClass().getMethod("cancel");
                cancelMethod.invoke(task);
            } catch (Exception ignored) {}
        }
    }

    // ──────────────────────────────────────────────────────
    //  REGION-BOUND TASKS (location may be null → global region)
    // ──────────────────────────────────────────────────────

    public static TaskWrapper runRegionTimer(Plugin plugin, Location location, Runnable task, long delayTicks, long periodTicks) {
        if (isFolia) {
            try {
                long initialDelay = Math.max(1, delayTicks);
                Consumer<?> consumer = (scheduledTask) -> task.run();
                Object scheduledTaskObj = regionRunTimerMethod.invoke(regionScheduler, plugin, location, consumer, initialDelay, periodTicks);
                return createWrapper(scheduledTaskObj);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
            return bukkitTask::cancel;
        }
        return () -> {};
    }

    public static TaskWrapper runRegionDelayed(Plugin plugin, Location location, Runnable task, long delayTicks) {
        if (isFolia) {
            try {
                Consumer<?> consumer = (scheduledTask) -> task.run();
                Object scheduledTaskObj = regionRunDelayedMethod.invoke(regionScheduler, plugin, location, consumer, delayTicks);
                return createWrapper(scheduledTaskObj);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
            return bukkitTask::cancel;
        }
        return () -> {};
    }

    public static void runRegion(Plugin plugin, Location location, Runnable task) {
        if (isFolia && location != null) {
            try {
                regionRunMethod.invoke(regionScheduler, plugin, location, task);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    // ──────────────────────────────────────────────────────
    //  ENTITY-BOUND TASKS
    // ──────────────────────────────────────────────────────

    /**
     * Runs a task bound to an entity's region (Folia) or the main thread.
     */
    public static void runForPlayer(Plugin plugin, HumanEntity player, Runnable runnable) {
        runEntity(plugin, player, runnable);
    }

    /**
     * Alias of {@link #runForPlayer(Plugin, HumanEntity, Runnable)}.
     */
    public static void runAtEntity(Plugin plugin, Entity entity, Runnable runnable) {
        runEntity(plugin, entity, runnable);
    }

    /**
     * Runs a task after a delay bound to an entity's region (Folia) or the main thread.
     */
    public static void runTaskLaterForPlayer(Plugin plugin, HumanEntity player, Runnable runnable, long delayTicks) {
        TaskWrapper wrapper = runEntityDelayed(plugin, player, runnable, delayTicks);
    }

    /**
     * Alias of {@link #runTaskLaterForPlayer(Plugin, HumanEntity, Runnable, long)}.
     */
    public static Object runAtEntityLater(Plugin plugin, Entity entity, Runnable runnable, long ticks) {
        return runEntityDelayed(plugin, entity, runnable, ticks);
    }

    /**
     * Runs a repeating task bound to an entity's region (Folia) or the main thread.
     */
    public static TaskWrapper runAtEntityTimer(Plugin plugin, Entity entity, Runnable runnable, long delay, long period) {
        return runEntityTimer(plugin, entity, runnable, delay, period);
    }

    public static TaskWrapper runEntityTimer(Plugin plugin, Entity entity, Runnable task, long delayTicks, long periodTicks) {
        if (isFolia && entityRunTimerMethod != null) {
            try {
                Object entityScheduler = entityGetSchedulerMethod.invoke(entity);
                long initialDelay = Math.max(1, delayTicks);
                Consumer<?> consumer = (scheduledTask) -> task.run();
                Object scheduledTaskObj = entityRunTimerMethod.invoke(entityScheduler, plugin, consumer, null, initialDelay, periodTicks);
                return createWrapper(scheduledTaskObj);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
            return bukkitTask::cancel;
        }
        return () -> {};
    }

    public static TaskWrapper runEntityDelayed(Plugin plugin, Entity entity, Runnable task, long delayTicks) {
        // Folia EntityScheduler has no delayed-only convenience matching our reflection cache;
        // schedule via a region-delayed task anchored at the entity's location.
        if (isFolia && entity != null) {
            try {
                Consumer<?> consumer = (scheduledTask) -> {
                    if (entity.isValid()) task.run();
                };
                Object scheduledTaskObj = regionRunDelayedMethod.invoke(regionScheduler, plugin, entity.getLocation(), consumer, delayTicks);
                return createWrapper(scheduledTaskObj);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        BukkitTask bukkitTask = Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        return bukkitTask::cancel;
    }

    public static void runEntity(Plugin plugin, Entity entity, Runnable task) {
        if (isFolia && entityRunMethod != null) {
            try {
                Object entityScheduler = entityGetSchedulerMethod.invoke(entity);
                Consumer<?> consumer = (scheduledTask) -> task.run();
                entityRunMethod.invoke(entityScheduler, plugin, consumer, null);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    // ──────────────────────────────────────────────────────
    //  HELPERS
    // ──────────────────────────────────────────────────────

    private static TaskWrapper createWrapper(Object foliaScheduledTask) {
        return () -> {
            try {
                Method cancelMethod = foliaScheduledTask.getClass().getMethod("cancel");
                cancelMethod.invoke(foliaScheduledTask);
            } catch (Exception ignored) {}
        };
    }
}
