package io.jenkins.plugins.pipelineoverview.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import hudson.init.Terminator;
import io.jenkins.plugins.pipelineoverview.stats.CustomStat;
import io.jenkins.plugins.pipelineoverview.stats.StatSource;
import io.jenkins.plugins.pipelineoverview.stats.StatValue;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CustomStatService {

    private static final Logger LOGGER = Logger.getLogger(CustomStatService.class.getName());

    private static final int STALE_MULTIPLIER = 3;

    private static final Cache<String, Entry> CACHE = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(1))
            .maximumSize(200)
            .build();

    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private static volatile ThreadPoolExecutor POOL = newRefreshPool();

    private static final long FETCH_TIMEOUT_MS = 30_000;

    private static volatile ScheduledExecutorService WATCHDOG = newWatchdog();

    private static ThreadPoolExecutor newRefreshPool() {
        return new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32),
                runnable -> {
                    Thread t = new Thread(runnable, "holistic-custom-stat-refresh");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static ScheduledExecutorService newWatchdog() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "holistic-custom-stat-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    private final LongSupplier clock;
    private final long fetchTimeoutMs;

    public CustomStatService() {
        this(System::currentTimeMillis, FETCH_TIMEOUT_MS);
    }

    CustomStatService(LongSupplier clock) {
        this(clock, FETCH_TIMEOUT_MS);
    }

    CustomStatService(LongSupplier clock, long fetchTimeoutMs) {
        this.clock = clock;
        this.fetchTimeoutMs = fetchTimeoutMs;
    }

    private static final class Entry {
        final StatValue value;
        final long lastAttemptAt;
        final long lastSuccessAt;

        Entry(StatValue value, long lastAttemptAt, long lastSuccessAt) {
            this.value = value;
            this.lastAttemptAt = lastAttemptAt;
            this.lastSuccessAt = lastSuccessAt;
        }
    }

    public JSONArray snapshot(List<CustomStat> stats) {
        JSONArray out = new JSONArray();
        if (stats == null || stats.isEmpty()) return out;
        long now = clock.getAsLong();
        for (CustomStat stat : stats) {
            try {
                out.add(render(stat, now));
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Custom stat '" + stat.getLabel() + "' failed to render", e);
                out.add(errorJson(stat));
            }
        }
        return out;
    }

    private JSONObject render(CustomStat stat, long now) {
        StatSource source = stat.getSource();
        if (source == null) return errorJson(stat);

        String key = source.cacheKey();
        Entry entry = CACHE.getIfPresent(key);
        long refreshMs = source.getRefreshSeconds() * 1000L;

        if (entry == null || now - entry.lastAttemptAt >= refreshMs) {
            scheduleRefresh(key, source, now);
        }

        JSONObject json = baseJson(stat);
        if (entry == null || entry.value == null) {
            json.put("state", entry == null ? "pending" : "error");
            return json;
        }

        StatValue value = entry.value;
        json.put("value", value.isNumeric() ? stripTrailingZero(value) : value.getDisplay());
        boolean stale = now - entry.lastSuccessAt > STALE_MULTIPLIER * refreshMs;
        json.put("state", stale ? "stale" : stat.thresholdState(value));
        return json;
    }

    private static Object stripTrailingZero(StatValue value) {
        double v = value.getNumeric();
        if (v == Math.rint(v)) return (long) v;
        return Double.parseDouble(value.getDisplay());
    }

    private JSONObject baseJson(CustomStat stat) {
        JSONObject json = new JSONObject();
        json.put("label", stat.getLabel());
        json.put("unit", stat.getUnit());
        if (stat.getCapacity() != null) json.put("capacity", stat.getCapacity());
        if (!stat.getLinkUrl().isEmpty()) json.put("link", stat.getLinkUrl());
        return json;
    }

    private JSONObject errorJson(CustomStat stat) {
        JSONObject json = baseJson(stat);
        json.put("state", "error");
        return json;
    }

    private void scheduleRefresh(String key, StatSource source, long attemptAt) {
        if (!IN_FLIGHT.add(key)) return;
        Future<?> task;
        try {
            task = POOL.submit(() -> {
                try {
                    StatValue value = source.fetch();
                    CACHE.put(key, new Entry(value, attemptAt, attemptAt));
                } catch (Exception e) {
                    Entry previous = CACHE.getIfPresent(key);
                    CACHE.put(key, new Entry(
                            previous != null ? previous.value : null,
                            attemptAt,
                            previous != null ? previous.lastSuccessAt : 0L));
                    LOGGER.log(Level.WARNING, "Custom stat refresh failed: " + e.getMessage());
                } finally {
                    IN_FLIGHT.remove(key);
                }
            });
        } catch (RejectedExecutionException e) {
            IN_FLIGHT.remove(key);
            LOGGER.log(Level.FINE, "Custom stat refresh queue is full, skipping this cycle");
            return;
        } catch (Throwable t) {
            IN_FLIGHT.remove(key);
            throw t;
        }
        try {
            WATCHDOG.schedule(() -> {
                if (task.cancel(true)) IN_FLIGHT.remove(key);
            }, fetchTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            if (task.cancel(true)) IN_FLIGHT.remove(key);
            LOGGER.log(Level.FINE, "Custom stat watchdog is unavailable, cancelling this refresh");
        }
    }

    @Terminator
    public static void shutdown() {
        POOL.shutdownNow();
        WATCHDOG.shutdownNow();
    }

    static void clearCacheForTesting() {
        CACHE.invalidateAll();
        IN_FLIGHT.clear();
    }

    static void awaitQuiescenceForTesting() throws InterruptedException {
        for (int i = 0; i < 200 && !IN_FLIGHT.isEmpty(); i++) {
            Thread.sleep(10);
        }
    }

    static int inFlightCountForTesting() {
        return IN_FLIGHT.size();
    }

    static boolean awaitExecutorTerminationForTesting(long timeoutMs) throws InterruptedException {
        return POOL.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
                && WATCHDOG.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
    }

    static void restartExecutorsForTesting() {
        POOL.shutdownNow();
        WATCHDOG.shutdownNow();
        POOL = newRefreshPool();
        WATCHDOG = newWatchdog();
    }
}
