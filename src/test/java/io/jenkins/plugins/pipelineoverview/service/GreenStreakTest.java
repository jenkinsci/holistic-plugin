package io.jenkins.plugins.pipelineoverview.service;

import io.jenkins.plugins.pipelineoverview.service.OverviewDataService.BuildRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GreenStreakTest {

    private static final long H = 60 * 60 * 1000L;

    private static BuildRecord build(int number, String result, long startTimeMs) {
        return new BuildRecord(number, result, 60_000, startTimeMs, false);
    }

    @Test
    void busyPipelineStreakStartsAtOldestGreenBuild() {
        List<BuildRecord> records = new ArrayList<>();
        records.add(build(80, "FAILURE", 100 * H));
        records.add(build(79, "FAILURE", 99 * H));
        for (int i = 0; i < 72; i++) {
            records.add(build(78 - i, "SUCCESS", (98 - i) * H));
        }
        records.add(build(6, "FAILURE", 26 * H));

        assertEquals(27 * H, OverviewDataService.greenStreakStartMs(records));
    }

    @Test
    void abortedAndRunningBuildsDoNotEndTheStreak() {
        List<BuildRecord> records = new ArrayList<>();
        records.add(new BuildRecord(10, null, 0, 50 * H, true));
        records.add(build(9, "FAILURE", 40 * H));
        records.add(build(8, "SUCCESS", 30 * H));
        records.add(build(7, "ABORTED", 20 * H));
        records.add(build(6, "SUCCESS", 10 * H));
        records.add(build(5, "UNSTABLE", 5 * H));

        assertEquals(10 * H, OverviewDataService.greenStreakStartMs(records));
    }

    @Test
    void noGreenBuildInWindow() {
        List<BuildRecord> records = List.of(build(2, "FAILURE", 2 * H), build(1, "FAILURE", H));

        assertEquals(0, OverviewDataService.greenStreakStartMs(records));
    }
}
