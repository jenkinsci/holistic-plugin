package io.jenkins.plugins.pipelineoverview.stats;

import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;

import java.io.IOException;
import java.io.Serializable;

public abstract class StatSource extends AbstractDescribableImpl<StatSource>
        implements ExtensionPoint, Serializable {
    private static final long serialVersionUID = 1L;

    public abstract StatValue fetch() throws IOException;

    public abstract String cacheKey();

    public abstract int getRefreshSeconds();

    public boolean referencesCredentials() {
        return false;
    }
}
