package io.jenkins.plugins.pipelineoverview.stats;

import java.io.Serializable;
import java.util.Locale;

public final class StatValue implements Serializable {
    private static final long serialVersionUID = 1L;

    private final Double numeric;
    private final String text;
    private final long fetchedAt;

    private StatValue(Double numeric, String text, long fetchedAt) {
        this.numeric = numeric;
        this.text = text;
        this.fetchedAt = fetchedAt;
    }

    public static StatValue numeric(double value, long fetchedAt) {
        return new StatValue(value, null, fetchedAt);
    }

    public static StatValue text(String value, long fetchedAt) {
        return new StatValue(null, value != null ? value : "", fetchedAt);
    }

    public boolean isNumeric() { return numeric != null; }

    public Double getNumeric() { return numeric; }

    public long getFetchedAt() { return fetchedAt; }

    public String getDisplay() {
        if (numeric == null) return text;
        if (numeric == Math.rint(numeric) && !numeric.isInfinite()) {
            return String.valueOf((long) (double) numeric);
        }
        return String.format(Locale.ROOT, "%.1f", numeric);
    }
}
