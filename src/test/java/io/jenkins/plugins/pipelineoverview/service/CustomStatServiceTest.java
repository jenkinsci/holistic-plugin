package io.jenkins.plugins.pipelineoverview.service;

import com.sun.net.httpserver.HttpServer;
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
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomStatServiceTest {

    private long now = 1_700_000_000_000L;

    private static final class FakeSource extends StatSource {
        private final AtomicInteger calls = new AtomicInteger();
        private final String key;
        StatValue next;
        IOException failure;

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
        public int getRefreshSeconds() { return 60; }
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
        server.createContext("/silent", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream os = exchange.getResponseBody();
            os.write('{');
            os.flush();
            handlerEntered.countDown();
            try {
                Thread.sleep(60_000);
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
            server.stop(0);
        }
    }
}
