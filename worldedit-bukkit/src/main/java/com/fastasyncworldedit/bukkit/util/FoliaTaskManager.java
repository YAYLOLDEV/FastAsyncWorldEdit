package com.fastasyncworldedit.bukkit.util;

import com.fastasyncworldedit.core.util.TaskManager;
import com.fastasyncworldedit.core.util.task.RunnableVal;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.BukkitEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.world.World;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nonnull;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class FoliaTaskManager extends TaskManager {

    private static final Class<?> REGIONIZED_SERVER = findClass("io.papermc.paper.threadedregions.RegionizedServer");
    private static final Class<?> TICK_THREAD = findClass("io.papermc.paper.util.TickThread");
    private static final Method IS_GLOBAL_TICK_THREAD = findMethod(Bukkit.class, "isGlobalTickThread");

    private final Plugin plugin;
    private final AtomicInteger taskIds = new AtomicInteger();
    private final Map<Integer, ScheduledTask> repeatingTasks = new ConcurrentHashMap<>();

    public FoliaTaskManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public static boolean isRegionized() {
        return REGIONIZED_SERVER != null;
    }

    public static boolean isTickThread() {
        return TICK_THREAD != null && TICK_THREAD.isInstance(Thread.currentThread());
    }

    public static boolean isGlobalTickThread() {
        if (IS_GLOBAL_TICK_THREAD == null) {
            return Bukkit.isPrimaryThread();
        }
        try {
            return (boolean) IS_GLOBAL_TICK_THREAD.invoke(null);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean isOwnedByCurrentRegion(org.bukkit.World world, int chunkX, int chunkZ) {
        if (!isRegionized()) {
            return Bukkit.isPrimaryThread();
        }
        return Bukkit.isOwnedByCurrentRegion(
                new org.bukkit.Location(world, chunkX << 4, world.getMinHeight(), chunkZ << 4)
        );
    }

    @Override
    public int repeat(@Nonnull Runnable runnable, int interval) {
        return repeat(runnable, 1, interval);
    }

    public int repeat(@Nonnull Runnable runnable, long delay, long interval) {
        return track(Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin,
                consume(runnable),
                Math.max(1, delay),
                Math.max(1, interval)
        ));
    }

    @Override
    public int repeatAsync(@Nonnull Runnable runnable, int interval) {
        return track(Bukkit.getAsyncScheduler().runAtFixedRate(
                plugin,
                consume(runnable),
                0,
                Math.multiplyExact(Math.max(1, interval), 50L),
                TimeUnit.MILLISECONDS
        ));
    }

    @Override
    public void async(@Nonnull Runnable runnable) {
        Bukkit.getAsyncScheduler().runNow(plugin, consume(runnable));
    }

    @Override
    public void task(@Nonnull Runnable runnable) {
        taskGlobal(runnable);
    }

    @Override
    public void task(@Nonnull Runnable runnable, @Nonnull World world, int chunkX, int chunkZ) {
        Bukkit.getRegionScheduler().run(plugin, BukkitAdapter.adapt(world), chunkX, chunkZ, consume(runnable));
    }

    @Override
    public void taskGlobal(@Nonnull Runnable runnable) {
        Bukkit.getGlobalRegionScheduler().run(plugin, consume(runnable));
    }

    @Override
    public void later(@Nonnull Runnable runnable, int delay) {
        laterGlobal(runnable, delay);
    }

    @Override
    public void later(@Nonnull Runnable runnable, @Nonnull com.sk89q.worldedit.util.Location location, int delay) {
        if (delay <= 0) {
            task(runnable, location);
        } else {
            Bukkit.getRegionScheduler().runDelayed(plugin, BukkitAdapter.adapt(location), consume(runnable), delay);
        }
    }

    @Override
    public void laterGlobal(@Nonnull Runnable runnable, int delay) {
        if (delay <= 0) {
            taskGlobal(runnable);
        } else {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, consume(runnable), delay);
        }
    }

    @Override
    public void laterAsync(@Nonnull Runnable runnable, int delay) {
        if (delay <= 0) {
            async(runnable);
            return;
        }
        Bukkit.getAsyncScheduler().runDelayed(
                plugin,
                consume(runnable),
                Math.multiplyExact(delay, 50L),
                TimeUnit.MILLISECONDS
        );
    }

    @Override
    public void cancel(int task) {
        ScheduledTask scheduledTask = repeatingTasks.remove(task);
        if (scheduledTask != null) {
            scheduledTask.cancel();
        }
    }

    @Override
    public void cancelAll() {
        repeatingTasks.values().forEach(ScheduledTask::cancel);
        repeatingTasks.clear();
    }

    @Override
    public <T> T sync(@Nonnull Supplier<T> supplier) {
        return syncGlobal(supplier);
    }

    @Override
    public <T> T syncWhenFree(@Nonnull Supplier<T> supplier) {
        return syncGlobal(supplier);
    }

    @Override
    public <T> T syncWhenFree(@Nonnull RunnableVal<T> function) {
        return syncGlobal(function);
    }

    @Override
    public <T> T syncAt(@Nonnull Supplier<T> supplier, @Nonnull World world, int chunkX, int chunkZ) {
        org.bukkit.World bukkitWorld = BukkitAdapter.adapt(world);
        if (isOwnedByCurrentRegion(bukkitWorld, chunkX, chunkZ)) {
            return supplier.get();
        }
        ensureCanBlock();
        FutureTask<T> task = new FutureTask<>(supplier::get);
        Bukkit.getRegionScheduler().run(plugin, bukkitWorld, chunkX, chunkZ, consume(task));
        return await(task);
    }

    @Override
    public <T> T syncWith(@Nonnull Supplier<T> supplier, @Nonnull Entity context) {
        org.bukkit.entity.Entity entity = adaptEntity(context);
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            return supplier.get();
        }
        ensureCanBlock();
        FutureTask<T> task = new FutureTask<>(supplier::get);
        if (!entity.getScheduler().execute(plugin, task, () -> task.cancel(false), 0)) {
            throw new IllegalStateException("Entity task could not be scheduled");
        }
        return await(task);
    }

    @Override
    public <T> T syncGlobal(@Nonnull Supplier<T> supplier) {
        if (isGlobalTickThread()) {
            return supplier.get();
        }
        ensureCanBlock();
        FutureTask<T> task = new FutureTask<>(supplier::get);
        Bukkit.getGlobalRegionScheduler().run(plugin, consume(task));
        return await(task);
    }

    private int track(ScheduledTask task) {
        int id = taskIds.incrementAndGet();
        repeatingTasks.put(id, task);
        return id;
    }

    private void ensureCanBlock() {
        if (isTickThread()) {
            throw new IllegalStateException(
                    "Cannot block a region tick thread while scheduling work for another region"
            );
        }
    }

    private static <T> T await(FutureTask<T> task) {
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (CancellationException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private static Consumer<ScheduledTask> consume(Runnable runnable) {
        return ignored -> runnable.run();
    }

    private static Class<?> findClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static org.bukkit.entity.Entity adaptEntity(Entity entity) {
        if (entity instanceof com.sk89q.worldedit.entity.Player player) {
            return BukkitAdapter.adapt(player);
        }
        if (entity instanceof BukkitEntity bukkitEntity) {
            org.bukkit.entity.Entity adapted = bukkitEntity.getEntity();
            if (adapted != null) {
                return adapted;
            }
        }
        throw new IllegalStateException("Entity is no longer available");
    }

}
