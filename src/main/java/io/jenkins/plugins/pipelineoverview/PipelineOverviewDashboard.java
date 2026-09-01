package io.jenkins.plugins.pipelineoverview;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Items;
import hudson.model.TopLevelItem;
import hudson.model.View;
import hudson.model.ViewDescriptor;
import hudson.model.ViewGroup;
import hudson.util.FormValidation;
import io.jenkins.plugins.pipelineoverview.service.CustomStatService;
import io.jenkins.plugins.pipelineoverview.service.OverviewDataService;
import io.jenkins.plugins.pipelineoverview.stats.CustomStat;
import io.jenkins.plugins.pipelineoverview.stats.StatSource;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.POST;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PipelineOverviewDashboard extends View {

    private static final Logger LOGGER =
            Logger.getLogger(PipelineOverviewDashboard.class.getName());

    private List<DashboardGroup> groups;
    private int refreshIntervalSeconds;
    private int historyDays;
    private String headerMessage;
    private String dashboardTitle;
    private boolean autoDiscover;
    private List<String> autoExcludeFolders;
    private List<CustomStat> customStats;
    private transient List<DashboardGroup> autoCache;
    private transient long autoCacheAt;

    @DataBoundConstructor
    public PipelineOverviewDashboard(String name) {
        super(name);
        initDefaults();
    }

    public PipelineOverviewDashboard(String name, ViewGroup owner) {
        super(name, owner);
        initDefaults();
    }

    private void initDefaults() {
        this.groups = new ArrayList<>();
        this.refreshIntervalSeconds = 30;
        this.historyDays = 30;
        this.headerMessage = "";
        this.dashboardTitle = "";
        this.autoDiscover = false;
        this.autoExcludeFolders = new ArrayList<>();
        this.customStats = new ArrayList<>();
    }


    public List<DashboardGroup> getGroups() {
        if (autoDiscover) return discoverAllPipelines();
        return groups != null ? groups : new ArrayList<>();
    }
    public List<DashboardGroup> getConfiguredGroups() {
        return groups != null ? groups : new ArrayList<>();
    }
    public int getRefreshIntervalSeconds() { return refreshIntervalSeconds; }
    public int getHistoryDays()            { return historyDays; }
    public boolean isAutoDiscover()        { return autoDiscover; }
    public List<String> getAutoExcludeFolders() {
        return autoExcludeFolders != null ? autoExcludeFolders : new ArrayList<>();
    }
    public List<CustomStat> getCustomStats() {
        return customStats != null ? customStats : new ArrayList<>();
    }
    public String getHeaderMessage() {
        return headerMessage != null ? headerMessage : "";
    }
    public String getDashboardTitle() {
        return (dashboardTitle != null && !dashboardTitle.isEmpty())
                ? dashboardTitle : getViewName();
    }


    @DataBoundSetter
    public void setGroups(List<DashboardGroup> groups) {
        this.groups = groups != null ? new ArrayList<>(groups) : new ArrayList<>();
    }

    @DataBoundSetter
    public void setRefreshIntervalSeconds(int v) {
        this.refreshIntervalSeconds = Math.max(5, v);
    }

    @DataBoundSetter
    public void setHistoryDays(int v) {
        this.historyDays = Math.max(1, Math.min(90, v));
    }

    @DataBoundSetter
    public void setHeaderMessage(String v) {
        this.headerMessage = v;
    }

    @DataBoundSetter
    public void setDashboardTitle(String v) {
        this.dashboardTitle = v;
    }

    @DataBoundSetter
    public void setAutoDiscover(boolean v) {
        this.autoDiscover = v;
        invalidateAutoCache();
    }

    @DataBoundSetter
    public void setAutoExcludeFolders(List<String> v) {
        this.autoExcludeFolders = v != null ? new ArrayList<>(v) : new ArrayList<>();
        invalidateAutoCache();
    }

    @DataBoundSetter
    public void setCustomStats(List<CustomStat> customStats) {
        this.customStats = customStats != null ? new ArrayList<>(customStats) : new ArrayList<>();
    }

    private synchronized void invalidateAutoCache() {
        this.autoCache = null;
    }

    private static final long AUTO_CACHE_TTL_MS = 30_000;

    private synchronized List<DashboardGroup> discoverAllPipelines() {
        long now = System.currentTimeMillis();
        if (autoCache != null && (now - autoCacheAt) < AUTO_CACHE_TTL_MS) return autoCache;

        Map<String, List<DashboardEntry>> byGroup = new TreeMap<>();
        java.util.List<String> exclude = getAutoExcludeFolders();

        ItemGroup<?> scope = getOwner() instanceof ItemGroup<?>
                ? (ItemGroup<?>) getOwner() : Jenkins.get();
        String scopePrefix = scope == Jenkins.get() ? "" : scope.getFullName() + "/";

        for (org.jenkinsci.plugins.workflow.job.WorkflowJob job :
                Items.allItems(scope, org.jenkinsci.plugins.workflow.job.WorkflowJob.class)) {
            if (!job.hasPermission(Item.READ)) continue;
            String fullName = job.getFullName();
            String relative = scopePrefix.isEmpty() ? fullName
                    : fullName.substring(scopePrefix.length());
            if (isPRBranch(relative)) continue;
            if (matchesAnyPrefix(relative, exclude)) continue;
            String g = inferGroupName(relative);
            byGroup.computeIfAbsent(g, k -> new ArrayList<>()).add(new DashboardEntry(fullName));
        }

        List<DashboardGroup> result = new ArrayList<>();
        for (Map.Entry<String, List<DashboardEntry>> e : byGroup.entrySet()) {
            DashboardGroup grp = new DashboardGroup(e.getKey());
            grp.setPipelines(e.getValue());
            result.add(grp);
        }
        autoCache = result;
        autoCacheAt = now;
        return result;
    }

    /** Heuristic: top-level folder name, or the first dash-segment of the leaf job name. */
    static String inferGroupName(String fullName) {
        String[] parts = fullName.split("/");
        String first = parts[0];
        // Folder containing pipelines (e.g. "sonar/X", "releases/X")
        if (parts.length > 1 && !first.contains("-")) return first.toUpperCase();
        // Multibranch / leaf with dash prefix
        int dash = first.indexOf('-');
        if (dash > 0) {
            String prefix = first.substring(0, dash);
            if (first.startsWith("scheduled-e2e")) return "E2E Tests";
            return prefix.toUpperCase();
        }
        return "Other";
    }

    private static boolean isPRBranch(String fullName) {
        int slash = fullName.lastIndexOf('/');
        if (slash < 0) return false;
        String leaf = fullName.substring(slash + 1);
        return leaf.startsWith("PR-");
    }

    private static boolean matchesAnyPrefix(String fullName, java.util.List<String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) return false;
        for (String p : prefixes) {
            if (p == null || p.isEmpty()) continue;
            if (fullName.equals(p) || fullName.startsWith(p + "/")) return true;
        }
        return false;
    }


    @Override
    public Collection<TopLevelItem> getItems() {
        Set<TopLevelItem> items = new LinkedHashSet<>();
        Jenkins jenkins = Jenkins.get();
        for (DashboardGroup group : getGroups()) {
            for (DashboardEntry entry : group.getPipelines()) {
                Item item = jenkins.getItemByFullName(entry.getJobName());
                if (item instanceof TopLevelItem && item.hasPermission(Item.READ)) {
                    items.add((TopLevelItem) item);
                }
            }
        }
        return items;
    }

    @Override
    public boolean contains(TopLevelItem item) {
        for (DashboardGroup group : getGroups()) {
            for (DashboardEntry entry : group.getPipelines()) {
                if (entry.getJobName().equals(item.getFullName())) return true;
            }
        }
        return false;
    }

    @Override
    protected void submit(StaplerRequest2 req)
            throws IOException, ServletException, Descriptor.FormException {
        JSONObject json = req.getSubmittedForm();

        Object statsData = json.opt("customStats");
        List<CustomStat> submittedStats = statsData != null
                ? req.bindJSONToList(CustomStat.class, statsData)
                : new ArrayList<>();
        if (referencesCredentials(submittedStats)
                && !Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            throw new Descriptor.FormException(
                    Messages.PipelineOverviewDashboard_CredentialsNeedAdminister(), "customStats");
        }

        this.refreshIntervalSeconds = Math.max(5, json.optInt("refreshIntervalSeconds", 30));
        this.historyDays = Math.max(1, Math.min(90, json.optInt("historyDays", 30)));
        this.headerMessage = json.optString("headerMessage", "");
        this.dashboardTitle = json.optString("dashboardTitle", "");
        this.autoDiscover = json.optBoolean("autoDiscover", false);
        invalidateAutoCache();

        Object groupsData = json.opt("groups");
        if (groupsData != null) {
            this.groups = req.bindJSONToList(DashboardGroup.class, groupsData);
        } else {
            this.groups = new ArrayList<>();
        }

        this.customStats = submittedStats;
    }

    private static boolean referencesCredentials(List<CustomStat> stats) {
        for (CustomStat stat : stats) {
            StatSource source = stat.getSource();
            if (source != null && source.referencesCredentials()) return true;
        }
        return false;
    }

    boolean renameJob(String oldFullName, String newFullName) {
        if (groups == null || newFullName == null || newFullName.equals(oldFullName)) return false;
        boolean changed = false;
        for (DashboardGroup group : groups) {
            for (DashboardEntry entry : group.getPipelines()) {
                if (entry.getJobName().equals(oldFullName)) {
                    entry.setJobName(newFullName);
                    changed = true;
                }
            }
        }
        return changed;
    }

    boolean removeJob(String fullName) {
        if (groups == null || fullName == null) return false;
        boolean changed = false;
        for (DashboardGroup group : groups) {
            changed |= group.getPipelines().removeIf(e -> fullName.equals(e.getJobName()));
        }
        return changed;
    }


    @POST
    public void doData(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        Jenkins.get().checkPermission(Jenkins.READ);

        rsp.setContentType("application/json;charset=UTF-8");
        rsp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");

        try {
            OverviewDataService service = new OverviewDataService();
            JSONObject result = service.fetchDashboardData(getGroups(), historyDays);

            result.put("viewName", getDashboardTitle());
            result.put("headerMessage", getHeaderMessage());

            try {
                JSONObject summary = result.optJSONObject("summary");
                if (summary != null) {
                    summary.put("customStats", new CustomStatService().snapshot(getCustomStats()));
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Custom stats unavailable for this refresh", e);
            }

            rsp.getWriter().write(result.toString());
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "Failed to build dashboard data", t);
            JSONObject error = new JSONObject();
            String msg = t.getMessage();
            error.put("error", (msg != null ? msg : t.getClass().getSimpleName()));
            error.put("timestamp", System.currentTimeMillis());
            rsp.getWriter().write(error.toString());
        }
    }


    @Extension
    @Symbol("holistic")
    public static class DescriptorImpl extends ViewDescriptor {

        @Override
        public String getDisplayName() {
            return Messages.PipelineOverviewDashboard_DisplayName();
        }

        @POST
        public FormValidation doCheckRefreshIntervalSeconds(@QueryParameter int value) {
            Jenkins.get().checkPermission(Jenkins.READ);
            if (value < 5) return FormValidation.error("Minimum is 5 seconds.");
            if (value > 3600) return FormValidation.warning("Consider a value under 300 seconds.");
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckHistoryDays(@QueryParameter int value) {
            Jenkins.get().checkPermission(Jenkins.READ);
            if (value < 1 || value > 90) return FormValidation.error("Must be between 1 and 90.");
            return FormValidation.ok();
        }
    }
}
