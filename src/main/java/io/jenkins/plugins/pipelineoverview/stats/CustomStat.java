package io.jenkins.plugins.pipelineoverview.stats;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import io.jenkins.plugins.pipelineoverview.Messages;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public class CustomStat extends AbstractDescribableImpl<CustomStat> implements Serializable {
    private static final long serialVersionUID = 1L;

    private String label;
    private StatSource source;
    private String unit;
    private Integer capacity;
    private Integer warnAt;
    private Integer critAt;
    private String linkUrl;

    @DataBoundConstructor
    public CustomStat(String label) {
        this.label = label != null ? label.trim() : "";
    }

    public String getLabel()     { return label != null ? label : ""; }
    public StatSource getSource() { return source; }
    public String getUnit()      { return unit != null ? unit : ""; }
    public Integer getCapacity() { return capacity; }
    public Integer getWarnAt()   { return warnAt; }
    public Integer getCritAt()   { return critAt; }
    public String getLinkUrl()   { return linkUrl != null ? linkUrl : ""; }

    @DataBoundSetter
    public void setSource(StatSource source) { this.source = source; }

    @DataBoundSetter
    public void setUnit(String unit) { this.unit = unit != null ? unit.trim() : ""; }

    @DataBoundSetter
    public void setCapacity(Integer capacity) { this.capacity = capacity; }

    @DataBoundSetter
    public void setWarnAt(Integer warnAt) { this.warnAt = warnAt; }

    @DataBoundSetter
    public void setCritAt(Integer critAt) { this.critAt = critAt; }

    @DataBoundSetter
    public void setLinkUrl(String linkUrl) {
        String trimmed = linkUrl != null ? linkUrl.trim() : "";
        this.linkUrl = isSafeUrl(trimmed) ? trimmed : "";
    }

    public String thresholdState(StatValue value) {
        if (value == null || !value.isNumeric()) return "ok";
        double v = value.getNumeric();
        if (warnAt == null && critAt == null) return "ok";
        if (warnAt == null) return v >= critAt ? "crit" : "ok";
        if (critAt == null) return v >= warnAt ? "warn" : "ok";
        if (critAt >= warnAt) {
            if (v >= critAt) return "crit";
            if (v >= warnAt) return "warn";
            return "ok";
        }
        if (v <= critAt) return "crit";
        if (v <= warnAt) return "warn";
        return "ok";
    }

    public static boolean isSafeUrl(String url) {
        if (url == null) return false;
        String trimmed = url.trim();
        if (trimmed.isEmpty() || trimmed.length() != url.length()) return false;
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            String lower = scheme.toLowerCase(Locale.ROOT);
            return ("http".equals(lower) || "https".equals(lower)) && uri.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<CustomStat> {

        @Override
        public String getDisplayName() {
            return Messages.CustomStat_DisplayName();
        }

        @POST
        public FormValidation doCheckLabel(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.READ);
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error(Messages.CustomStat_LabelRequired());
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckLinkUrl(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.READ);
            if (value == null || value.trim().isEmpty()) return FormValidation.ok();
            if (!isSafeUrl(value)) {
                return FormValidation.error(Messages.CustomStat_UrlSchemeInvalid());
            }
            return FormValidation.ok();
        }
    }
}
