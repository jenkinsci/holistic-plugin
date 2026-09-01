package io.jenkins.plugins.pipelineoverview;

import com.sun.net.httpserver.HttpServer;
import io.jenkins.plugins.pipelineoverview.stats.CustomStat;
import io.jenkins.plugins.pipelineoverview.stats.HttpJsonStatSource;
import io.jenkins.plugins.pipelineoverview.stats.JsonPointerExtractor;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
class CustomStatsViewTest {

    private static CustomStat previewEnvsStat() {
        CustomStat stat = new CustomStat("Preview Envs");
        stat.setCapacity(5);
        stat.setWarnAt(4);
        stat.setCritAt(5);
        stat.setLinkUrl("https://argocd.example.com/applications");
        HttpJsonStatSource source = new HttpJsonStatSource("https://argocd.example.com/api/v1/applications");
        source.setPointer("/items");
        source.setMode(JsonPointerExtractor.Mode.COUNT);
        stat.setSource(source);
        return stat;
    }

    @Test
    void customStatsDefaultToEmpty(JenkinsRule j) {
        PipelineOverviewDashboard view = new PipelineOverviewDashboard("dash");
        assertNotNull(view.getCustomStats());
        assertTrue(view.getCustomStats().isEmpty());
    }

    @Test
    void customStatsSurviveAConfigRoundTrip(JenkinsRule j) throws Exception {
        PipelineOverviewDashboard view = new PipelineOverviewDashboard("dash");
        view.setCustomStats(List.of(previewEnvsStat()));
        j.jenkins.addView(view);

        j.configRoundtrip(view);

        PipelineOverviewDashboard reloaded =
                (PipelineOverviewDashboard) j.jenkins.getView("dash");
        assertEquals(1, reloaded.getCustomStats().size());
        CustomStat stat = reloaded.getCustomStats().get(0);
        assertEquals("Preview Envs", stat.getLabel());
        assertEquals(5, stat.getCapacity());
        assertEquals(4, stat.getWarnAt());
        assertEquals(5, stat.getCritAt());
        HttpJsonStatSource source = assertInstanceOf(HttpJsonStatSource.class, stat.getSource());
        assertEquals("/items", source.getPointer());
        assertEquals(JsonPointerExtractor.Mode.COUNT, source.getMode());
    }

    @Test
    void dataEndpointCarriesCustomStats(JenkinsRule j) throws Exception {
        resetCustomStatServiceExecutors();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/envs", exchange -> {
            byte[] body = "{\"items\":[1,2,3,4]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            CustomStat stat = new CustomStat("Preview Envs");
            stat.setCapacity(5);
            stat.setWarnAt(4);
            stat.setCritAt(5);
            HttpJsonStatSource source = new HttpJsonStatSource(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/envs");
            source.setPointer("/items");
            source.setMode(JsonPointerExtractor.Mode.COUNT);
            stat.setSource(source);

            PipelineOverviewDashboard view = new PipelineOverviewDashboard("dash");
            view.setCustomStats(List.of(stat));
            j.jenkins.addView(view);

            JenkinsRule.WebClient wc = j.createWebClient();

            JSONObject payload = null;
            JSONObject customStat = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (customStat == null && System.nanoTime() < deadline) {
                payload = fetchData(j, wc);
                JSONArray customStats = payload.getJSONObject("summary").getJSONArray("customStats");
                JSONObject candidate = customStats.getJSONObject(0);
                if (!"pending".equals(candidate.getString("state"))) {
                    customStat = candidate;
                } else {
                    Thread.sleep(100);
                }
            }

            assertNotNull(customStat,
                    "custom stat never left the pending state within 10 seconds: " + payload);
            assertEquals("Preview Envs", customStat.getString("label"));
            assertEquals(4, customStat.getInt("value"));
            assertEquals("warn", customStat.getString("state"));
            assertTrue(payload.getJSONObject("summary").has("weekSuccessRate"),
                    "built-in summary must still render: " + payload);
        } finally {
            server.stop(0);
        }
    }

    private static void resetCustomStatServiceExecutors() throws Exception {
        Class<?> service = Class.forName("io.jenkins.plugins.pipelineoverview.service.CustomStatService");
        Method clearCache = service.getDeclaredMethod("clearCacheForTesting");
        clearCache.setAccessible(true);
        clearCache.invoke(null);
        Method restartExecutors = service.getDeclaredMethod("restartExecutorsForTesting");
        restartExecutors.setAccessible(true);
        restartExecutors.invoke(null);
    }

    private JSONObject fetchData(JenkinsRule j, JenkinsRule.WebClient wc) throws Exception {
        return post(j, wc, "view/dash/data");
    }

    private JSONObject post(JenkinsRule j, JenkinsRule.WebClient wc, String path) throws Exception {
        WebRequest request = wc.addCrumb(new WebRequest(new URL(j.getURL(), path), HttpMethod.POST));
        String json = wc.getPage(request).getWebResponse().getContentAsString();
        return JSONObject.fromObject(json);
    }

    @Test
    void fullScreenEndpointCarriesCustomStats(JenkinsRule j) throws Exception {
        resetCustomStatServiceExecutors();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/envs", exchange -> {
            byte[] body = "{\"items\":[1,2,3]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            CustomStat stat = new CustomStat("Preview Envs");
            stat.setCapacity(5);
            HttpJsonStatSource source = new HttpJsonStatSource(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/envs");
            source.setPointer("/items");
            source.setMode(JsonPointerExtractor.Mode.COUNT);
            stat.setSource(source);

            PipelineOverviewDashboard view = new PipelineOverviewDashboard("dash");
            view.setCustomStats(List.of(stat));
            j.jenkins.addView(view);

            JenkinsRule.WebClient wc = j.createWebClient();

            JSONObject payload = null;
            JSONObject customStat = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (customStat == null && System.nanoTime() < deadline) {
                payload = post(j, wc, "pipeline-overview/data");
                JSONArray stats = payload.getJSONObject("summary").getJSONArray("customStats");
                JSONObject candidate = stats.getJSONObject(0);
                if (!"pending".equals(candidate.getString("state"))) {
                    customStat = candidate;
                } else {
                    Thread.sleep(100);
                }
            }

            assertNotNull(customStat,
                    "full screen endpoint never resolved the stat within 10 seconds: " + payload);
            assertEquals("Preview Envs", customStat.getString("label"));
            assertEquals(3, customStat.getInt("value"));
            assertTrue(payload.getJSONObject("summary").has("weekSuccessRate"),
                    "built-in summary must still render: " + payload);
        } finally {
            server.stop(0);
        }
    }
}
