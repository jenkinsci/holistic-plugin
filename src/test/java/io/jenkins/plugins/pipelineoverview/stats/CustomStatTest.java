package io.jenkins.plugins.pipelineoverview.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomStatTest {

    private static final long AT = 1_700_000_000_000L;

    private static CustomStat stat(Integer warnAt, Integer critAt) {
        CustomStat s = new CustomStat("Preview Envs");
        s.setWarnAt(warnAt);
        s.setCritAt(critAt);
        return s;
    }

    @Test
    void noThresholdsIsAlwaysOk() {
        assertEquals("ok", stat(null, null).thresholdState(StatValue.numeric(999, AT)));
    }

    @Test
    void higherIsWorseWhenCritAboveWarn() {
        CustomStat s = stat(4, 5);
        assertEquals("ok", s.thresholdState(StatValue.numeric(3, AT)));
        assertEquals("warn", s.thresholdState(StatValue.numeric(4, AT)));
        assertEquals("crit", s.thresholdState(StatValue.numeric(5, AT)));
        assertEquals("crit", s.thresholdState(StatValue.numeric(6, AT)));
    }

    @Test
    void lowerIsWorseWhenCritBelowWarn() {
        CustomStat s = stat(95, 90);
        assertEquals("ok", s.thresholdState(StatValue.numeric(97, AT)));
        assertEquals("warn", s.thresholdState(StatValue.numeric(95, AT)));
        assertEquals("crit", s.thresholdState(StatValue.numeric(90, AT)));
        assertEquals("crit", s.thresholdState(StatValue.numeric(12, AT)));
    }

    @Test
    void equalThresholdsMeanHigherIsWorseAndNeverAmber() {
        CustomStat s = stat(5, 5);
        assertEquals("ok", s.thresholdState(StatValue.numeric(4, AT)));
        assertEquals("crit", s.thresholdState(StatValue.numeric(5, AT)));
    }

    @Test
    void onlyWarnSetNeverGoesCritical() {
        CustomStat s = stat(3, null);
        assertEquals("ok", s.thresholdState(StatValue.numeric(2, AT)));
        assertEquals("warn", s.thresholdState(StatValue.numeric(30, AT)));
    }

    @Test
    void onlyCritSetNeverGoesAmber() {
        CustomStat s = stat(null, 3);
        assertEquals("ok", s.thresholdState(StatValue.numeric(2, AT)));
        assertEquals("crit", s.thresholdState(StatValue.numeric(3, AT)));
    }

    @Test
    void textValueIsAlwaysOk() {
        assertEquals("ok", stat(4, 5).thresholdState(StatValue.text("aws-test", AT)));
    }

    @Test
    void labelIsTrimmed() {
        assertEquals("Preview Envs", new CustomStat("  Preview Envs  ").getLabel());
    }

    @Test
    void safeUrlAcceptsHttpAndHttpsOnly() {
        assertTrue(CustomStat.isSafeUrl("https://argocd.k8s.gomspace.lan/applications"));
        assertTrue(CustomStat.isSafeUrl("http://localhost:8080/x"));
        assertFalse(CustomStat.isSafeUrl("javascript:alert(1)"));
        assertFalse(CustomStat.isSafeUrl("file:///etc/passwd"));
        assertFalse(CustomStat.isSafeUrl("jar:file:///x!/y"));
        assertFalse(CustomStat.isSafeUrl("JAVASCRIPT:alert(1)"));
        assertFalse(CustomStat.isSafeUrl("  javascript:alert(1)"));
        assertFalse(CustomStat.isSafeUrl(""));
        assertFalse(CustomStat.isSafeUrl(null));
    }
}
