package io.jenkins.plugins.pipelineoverview;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import java.io.Serializable;

/**
 * A single pipeline job to monitor on the overview dashboard.
 */
public class DashboardEntry extends AbstractDescribableImpl<DashboardEntry> implements Serializable {
    private static final long serialVersionUID = 1L;

    private String jobName;
    private boolean enabled;

    @DataBoundConstructor
    public DashboardEntry(String jobName) {
        this.jobName = jobName != null ? jobName.trim() : "";
        this.enabled = true;
    }

    public String getJobName()    { return jobName; }
    public boolean isEnabled()    { return enabled; }

    public void setJobName(String jobName) {
        this.jobName = jobName != null ? jobName.trim() : "";
    }

    @DataBoundSetter
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<DashboardEntry> {

        @Override
        public String getDisplayName() {
            return Messages.DashboardEntry_DisplayName();
        }

        @POST
        public FormValidation doCheckJobName(@QueryParameter String value) {
            Jenkins jenkins = Jenkins.get();
            jenkins.checkPermission(Jenkins.READ);
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error(Messages.DashboardEntry_JobNameRequired());
            }
            Item item = jenkins.getItemByFullName(value.trim());
            if (item == null) {
                return FormValidation.warning(Messages.DashboardEntry_JobNotFound(value.trim()));
            }
            if (!(item instanceof WorkflowJob)) {
                return FormValidation.warning(Messages.DashboardEntry_NotAPipeline(value.trim()));
            }
            return FormValidation.ok();
        }

        @POST
        public AutoCompletionCandidates doAutoCompleteJobName(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.READ);
            AutoCompletionCandidates candidates = new AutoCompletionCandidates();
            if (value == null || value.length() < 2) return candidates;
            String lower = value.toLowerCase();
            int count = 0;
            for (Item item : Jenkins.get().allItems(Item.class)) {
                if (count >= 25) break;
                if (item instanceof WorkflowJob
                        && item.getFullName().toLowerCase().contains(lower)
                        && item.hasPermission(Item.READ)) {
                    candidates.add(item.getFullName());
                    count++;
                }
            }
            return candidates;
        }
    }
}
