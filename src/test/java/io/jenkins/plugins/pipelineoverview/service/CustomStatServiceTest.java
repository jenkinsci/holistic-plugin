package io.jenkins.plugins.pipelineoverview.service;

import com.sun.net.httpserver.HttpServer;
import hudson.init.Terminator;
import io.jenkins.plugins.pipelineoverview.stats.CustomStat;
import io.jenkins.plugins.pipelineoverview.stats.HttpJsonStatSource;
import io.jenkins.plugins.pipelineoverview.stats.StatSource;
import io.jenkins.plugins.pipelineoverview.stats.StatValue;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomStatServiceTest {

    private long now = 1_700_000_000_000L;

    private static final class FakeSource extends StatSource {
        private final AtomicInteger calls = new AtomicInteger();
        private final String key;
        StatValue next;
        IOException failure;
        int refreshSeconds = 60;

        FakeSource(String key, StatValue next) {
            this.key = key;
            this.next = next;
        }

        @Override
        public StatValue fetch() throws IOException {
            calls.incrementAndGet();
            if (failure != null) throw failure;
            return next;
        }

        @Override
        public String cacheKey() { return key; }

        @Override
        public int getRefreshSeconds() { return refreshSeconds; }
    }

    private CustomStat stat(String label, StatSource source) {
        CustomStat s = new CustomStat(label);
        s.setSource(source);
        return s;
    }

    private JSONObject only(JSONArray array) {
        assertEquals(1, array.size());
        return array.getJSONObject(0);
    }

    @BeforeEach
    void reset() {
        CustomStatService.restartExecutorsForTesting();
        CustomStatService.clearCacheForTesting();
    }

    private CustomStatService service() {
        return new CustomStatService(() -> now);
    }

    @Test
    void firstSnapshotIsPendingThenFillsIn() throws Exception {
        FakeSource src = new FakeSource("k1", StatValue.numeric(4, now));
        CustomStat s = stat("Preview Envs", src);

        JSONObject first = only(service().snapshot(List.of(s)));
        assertEquals("pending", first.getString("state"));

        CustomStatService.awaitQuiescenceForTesting();
        JSONObject second = only(service().snapshot(List.of(s)));
        assertEquals("ok", second.getString("state"));
        assertEquals(4, second.getInt("value"));
        assertEquals("Preview Envs", second.getString("label"));
    }

    @Test
    void thresholdsColourTheValue() throws Exception {
        FakeSource src = new FakeSource("k2", StatValue.numeric(5, now));
        CustomStat s = stat("Preview Envs", src);
        s.setWarnAt(4);
        s.setCritAt(5);
        s.setCapacity(5);

        service().snapshot(List.of(s));
        CustomStatService.awaitQuiescenceForTesting();

        JSONObject json = only(service().snapshot(List.of(s)));
        assertEquals("crit", json.getString("state"));
        assertEquals(5, json.getInt("capacity"));
    }

    @Test
    void valueIsNotRefetchedInsideTheRefreshInterval() throws Exception {
        FakeSource src = new FakeSource("k3", StatValue.numeric(1, now));
        CustomStat s = stat("A", src);

        service().snapshot(List.of(s));
        CustomStatService.awaitQuiescenceForTesting();
        now += 30_000;
        service().snapshot(List.of(s));
        CustomStatService.awaitQuiescenceForTesting();

        assertEquals(1, src.calls.get());
    }

    @Test
    void valueIsRefetchedAfterTheRefreshInterval() throws Exception {
        FakeSource src = new FakeSource("k4", StatValue.numeric(1, now));
        CustomStat s = stat("A", src);

        service().snapshot(List.of(s));
        CustomStatService.awaitQuiescenceForTesting();
        now += 61_000;
        service().snapshot(List.of(s));
        CustomStatService.awaitQuiescenceForTesting();

        assertEquals(2, src.calls.get());
    }

    @Test
    void failureWithNoPreviousValueIsError() throws Exception {
        FakeSource src = new FakeSource("k5", null);
        src.failure = new IOException("boom");
        CustomStat s = stat("A", src);

        service().snapshot(List.of(s));
        CustomStatService.awaitQuiescenceForTesting();

        assertEquals("error", only(service().snapshot(List.of(s))).getString("state"));
    }

    @Test
    void recentFailureKeepsTheLastGoodValue() throws Exception {
        FakeSource src = new FakeSource("k6", StatValue.numeric(4, now));
        CustomStat s = stat("A", src);

        service().snapshot(List.of(s));
        CustomStatService.awaitQuiescenceForTesting();

        src.failure = new IOException("boom");
        now += 61_000;
        service().snapshot(List.of(s));
        CustomStatService.awaitQuiescenceForTesting();

        JSONObject json = only(service().snapshot(List.of(s)));
        assertEquals("ok", json.getString("state"));
        assertEquals(4, json.getInt("value"));
    }

    @Test
    void staleAfterThreeRefreshIntervalsWithoutSuccess() throws Exception {
        FakeSource src = new FakeSource("k7", StatValue.numeric(4, now));
        CustomStat s = stat("A", src);

        service().snapshot(List.of(s));
        CustomStatService.awaitQuiescenceForTesting();

        src.failure = new IOException("boom");
        now += 200_000;
        service().snapshot(List.of(s));
        CustomStatService.awaitQuiescenceForTesting();

        JSONObject json = only(service().snapshot(List.of(s)));
        assertEquals("stale", json.getString("state"));
        assertEquals(4, json.getInt("value"));
    }

    @Test
    void twoStatsSharingASourceFetchOnce() throws Exception {
        FakeSource src = new FakeSource("k8", StatValue.numeric(2, now));
        CustomStat a = stat("A", src);
        CustomStat b = stat("B", src);

        service().snapshot(List.of(a, b));
        CustomStatService.awaitQuiescenceForTesting();

        assertEquals(1, src.calls.get());
        assertEquals(2, service().snapshot(List.of(a, b)).size());
    }

    @Test
    void statWithoutASourceIsError() {
        JSONObject json = only(service().snapshot(List.of(new CustomStat("A"))));
        assertEquals("error", json.getString("state"));
    }

    @Test
    void textValueIsCarriedThrough() throws Exception {
        FakeSource src = new FakeSource("k9", StatValue.text("aws-test", now));
        CustomStat s = stat("Cluster", src);

        service().snapshot(List.of(s));
        CustomStatService.awaitQuiescenceForTesting();

        JSONObject json = only(service().snapshot(List.of(s)));
        assertEquals("ok", json.getString("state"));
        assertEquals("aws-test", json.getString("value"));
    }

    @Test
    void linkAndUnitAreIncludedWhenSet() throws Exception {
        FakeSource src = new FakeSource("k10", StatValue.numeric(4, now));
        CustomStat s = stat("A", src);
        s.setUnit("envs");
        s.setLinkUrl("https://argocd.k8s.gomspace.lan/applications");

        service().snapshot(List.of(s));
        CustomStatService.awaitQuiescenceForTesting();

        JSONObject json = only(service().snapshot(List.of(s)));
        assertEquals("envs", json.getString("unit"));
        assertTrue(json.getString("link").startsWith("https://argocd"));
    }

    @Test
    void aSourceWithoutARefreshIntervalIsFlooredByTheService() throws Exception {
        FakeSource src = new FakeSource("k-floor", StatValue.numeric(3, now));
        src.refreshSeconds = 0;
        CustomStat s = stat("A", src);

        service().snapshot(List.of(s));
        CustomStatService.awaitQuiescenceForTesting();
        now += 1;

        JSONObject json = only(service().snapshot(List.of(s)));
        assertEquals("ok", json.getString("state"), "a zero interval must not read as stale");
        assertEquals(3, json.getInt("value"));

        CustomStatService.awaitQuiescenceForTesting();
        assertEquals(1, src.calls.get(), "a zero interval must not refetch on every snapshot");
    }

    @Test
    void nonFiniteValueIsAnErrorRatherThanANonsenseNumber() throws Exception {
        FakeSource src = new FakeSource("k-inf", StatValue.numeric(Double.POSITIVE_INFINITY, now));
        CustomStat s = stat("A", src);

        service().snapshot(List.of(s));
        CustomStatService.awaitQuiescenceForTesting();

        JSONObject json = only(service().snapshot(List.of(s)));
        assertEquals("error", json.getString("state"));
        assertFalse(json.has("value"), "an infinite value must not be rendered: " + json);
    }

    private static final class CapturingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        synchronized List<LogRecord> matching(String needle) {
            return records.stream()
                    .filter(r -> r.getMessage() != null && r.getMessage().contains(needle))
                    .toList();
        }
    }

    private static CapturingHandler captureServiceLog() {
        CapturingHandler handler = new CapturingHandler();
        handler.setLevel(Level.ALL);
        Logger logger = Logger.getLogger(CustomStatService.class.getName());
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        return handler;
    }

    private static void stopCapturing(CapturingHandler handler) {
        Logger.getLogger(CustomStatService.class.getName()).removeHandler(handler);
    }

    @Test
    void onlyTheFirstOfARunOfFailuresLogsAtWarning() throws Exception {
        CapturingHandler handler = captureServiceLog();
        try {
            FakeSource src = new FakeSource("k-noisy", null);
            src.failure = new IOException("endpoint is down");
            CustomStat s = stat("A", src);

            for (int i = 0; i < 3; i++) {
                service().snapshot(List.of(s));
                CustomStatService.awaitQuiescenceForTesting();
                now += 61_000;
            }
        } finally {
            stopCapturing(handler);
        }

        List<LogRecord> failures = handler.matching("k-noisy");
        assertEquals(3, failures.size(), "every failure should still reach the log");
        assertEquals(Level.WARNING, failures.get(0).getLevel());
        assertEquals(Level.FINE, failures.get(1).getLevel());
        assertEquals(Level.FINE, failures.get(2).getLevel());
        assertNotNull(failures.get(0).getThrown(), "the exception itself must be logged");
    }

    @Test
    void aSuccessResetsTheFailureLogThrottle() throws Exception {
        CapturingHandler handler = captureServiceLog();
        try {
            FakeSource src = new FakeSource("k-flapping", StatValue.numeric(1, now));
            src.failure = new IOException("endpoint is down");
            CustomStat s = stat("A", src);

            service().snapshot(List.of(s));
            CustomStatService.awaitQuiescenceForTesting();

            src.failure = null;
            now += 61_000;
            service().snapshot(List.of(s));
            CustomStatService.awaitQuiescenceForTesting();

            src.failure = new IOException("endpoint is down again");
            now += 61_000;
            service().snapshot(List.of(s));
            CustomStatService.awaitQuiescenceForTesting();
        } finally {
            stopCapturing(handler);
        }

        List<LogRecord> failures = handler.matching("k-flapping");
        assertEquals(2, failures.size());
        assertEquals(Level.WARNING, failures.get(0).getLevel());
        assertEquals(Level.WARNING, failures.get(1).getLevel(),
                "a success in between must reset the throttle");
    }

    @Test
    void emptyListGivesEmptyArray() {
        assertEquals(0, service().snapshot(List.of()).size());
    }

    private static final class BlockingSource extends StatSource {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);

        @Override
        public StatValue fetch() throws IOException {
            entered.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted.countDown();
                throw new IOException("interrupted", e);
            }
            return null;
        }

        @Override
        public String cacheKey() { return "blocking"; }

        @Override
        public int getRefreshSeconds() { return 60; }
    }

    @Test
    void watchdogInterruptsAFetchThatNeverReturns() throws Exception {
        BlockingSource src = new BlockingSource();
        CustomStat s = stat("A", src);

        new CustomStatService(() -> now, 300).snapshot(List.of(s));

        assertTrue(src.entered.await(5, TimeUnit.SECONDS), "fetch should have started");
        assertTrue(src.interrupted.await(10, TimeUnit.SECONDS),
                "watchdog should have interrupted the blocked fetch");
        CustomStatService.awaitQuiescenceForTesting();
        assertEquals(0, CustomStatService.inFlightCountForTesting());
    }

    @Test
    void watchdogReleasesAThreadBlockedOnASilentSocket() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch handlerRelease = new CountDownLatch(1);
        server.createContext("/silent", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream os = exchange.getResponseBody();
            os.write('{');
            os.flush();
            handlerEntered.countDown();
            try {
                handlerRelease.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            HttpJsonStatSource source = new HttpJsonStatSource(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/silent");
            source.setPointer("/a");
            CustomStat s = stat("Silent", source);

            new CustomStatService(() -> now, 500).snapshot(List.of(s));

            assertTrue(handlerEntered.await(5, TimeUnit.SECONDS), "server should have been reached");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (CustomStatService.inFlightCountForTesting() > 0
                    && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(0, CustomStatService.inFlightCountForTesting(),
                    "watchdog must release a worker blocked reading a silent socket");
        } finally {
            handlerRelease.countDown();
            server.stop(0);
        }
    }

    private static ThreadPoolExecutor refreshPool() throws Exception {
        Field field = CustomStatService.class.getDeclaredField("POOL");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(null);
    }

    @Test
    void aSubmitThatThrowsDoesNotWedgeTheKey() throws Exception {
        ThreadPoolExecutor pool = refreshPool();
        ThreadFactory original = pool.getThreadFactory();
        FakeSource src = new FakeSource("k-submit-throws", StatValue.numeric(1, now));
        JSONObject json;
        try {
            pool.setMaximumPoolSize(8);
            pool.setCorePoolSize(8);
            pool.setThreadFactory(runnable -> {
                throw new SecurityException("thread creation denied");
            });

            json = only(service().snapshot(List.of(stat("A", src))));
        } finally {
            pool.setThreadFactory(original);
            pool.setCorePoolSize(2);
            pool.setMaximumPoolSize(2);
            pool.getQueue().clear();
        }

        assertEquals("error", json.getString("state"));
        assertEquals(0, src.calls.get(), "the refresh must never have run");
        assertEquals(0, CustomStatService.inFlightCountForTesting(),
                "a submit that throws must not leave the key in flight");
    }

    @Test
    void terminatorShutsDownBothExecutors() throws Exception {
        Method shutdown = CustomStatService.class.getDeclaredMethod("shutdown");
        assertTrue(Modifier.isStatic(shutdown.getModifiers()), "the terminator must be static");
        assertTrue(shutdown.isAnnotationPresent(Terminator.class),
                "Jenkins only runs the shutdown if it is annotated as a terminator");
        try {
            CustomStatService.shutdown();

            assertTrue(CustomStatService.awaitExecutorTerminationForTesting(5_000),
                    "the terminator must shut down both executors");
        } finally {
            CustomStatService.restartExecutorsForTesting();
        }
    }
}
