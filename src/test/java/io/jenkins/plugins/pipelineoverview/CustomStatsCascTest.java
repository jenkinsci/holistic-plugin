package io.jenkins.plugins.pipelineoverview;

import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import io.jenkins.plugins.pipelineoverview.stats.CustomStat;
import io.jenkins.plugins.pipelineoverview.stats.HttpJsonStatSource;
import io.jenkins.plugins.pipelineoverview.stats.JsonPointerExtractor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkinsConfiguredWithCode
class CustomStatsCascTest {

    @Test
    @ConfiguredWithCode("custom-stats-casc.yml")
    void customStatsAreConfigurableAsCode(JenkinsConfiguredWithCodeRule j) {
        PipelineOverviewDashboard view =
                (PipelineOverviewDashboard) j.jenkins.getView("Pipeline Overview");
        assertEquals(1, view.getCustomStats().size());

        CustomStat stat = view.getCustomStats().get(0);
        assertEquals("Preview Envs", stat.getLabel());
        assertEquals(5, stat.getCapacity());
        assertEquals(4, stat.getWarnAt());
        assertEquals(5, stat.getCritAt());
        assertTrue(stat.getLinkUrl().startsWith("https://argocd.example.com"));

        HttpJsonStatSource source = assertInstanceOf(HttpJsonStatSource.class, stat.getSource());
        assertEquals("/items", source.getPointer());
        assertEquals(JsonPointerExtractor.Mode.COUNT, source.getMode());
        assertEquals(60, source.getRefreshSeconds());
    }
}
