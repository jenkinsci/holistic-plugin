package io.jenkins.plugins.pipelineoverview;

import io.jenkins.plugins.pipelineoverview.stats.CustomStat;
import io.jenkins.plugins.pipelineoverview.stats.HttpJsonStatSource;
import io.jenkins.plugins.pipelineoverview.stats.JsonPointerExtractor;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.net.URL;
import java.util.List;

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
        PipelineOverviewDashboard view = new PipelineOverviewDashboard("dash");
        view.setCustomStats(List.of(previewEnvsStat()));
        j.jenkins.addView(view);

        JenkinsRule.WebClient wc = j.createWebClient();
        WebRequest request = wc.addCrumb(new WebRequest(
                new URL(j.getURL(), "view/dash/data"),
                HttpMethod.POST));
        String json = wc.getPage(request).getWebResponse().getContentAsString();

        assertTrue(json.contains("customStats"), "payload should carry customStats: " + json);
        assertTrue(json.contains("Preview Envs"));
        assertTrue(json.contains("weekSuccessRate"), "built-in summary must still render");
    }
}
