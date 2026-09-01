package io.jenkins.plugins.pipelineoverview;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.RootAction;
import hudson.model.View;
import hudson.model.ViewGroup;
import io.jenkins.plugins.pipelineoverview.service.OverviewDataService;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.POST;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sidebar entry that opens the dashboard full-screen. Only shown when exactly one
 * {@link PipelineOverviewDashboard} view is configured anywhere in Jenkins; with
 * zero or multiple matches the link hides itself rather than guessing.
 */
@Extension
public class PipelineOverviewLink implements RootAction {

    private static final Logger LOGGER =
            Logger.getLogger(PipelineOverviewLink.class.getName());

    @Override
    public String getIconFileName() {
        return findUniqueDashboardView() != null
                ? "symbol-tv-outline plugin-ionicons-api" : null;
    }

    @Override
    public String getDisplayName() {
        return "Pipeline Overview";
    }

    @Override
    public String getUrlName() {
        return "pipeline-overview";
    }

    public PipelineOverviewDashboard getView() {
        return findUniqueDashboardView();
    }

    @POST
    public void doData(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        Jenkins.get().checkPermission(Jenkins.READ);

        rsp.setContentType("application/json;charset=UTF-8");
        rsp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");

        PipelineOverviewDashboard view = findUniqueDashboardView();
        if (view == null) {
            JSONObject error = new JSONObject();
            error.put("error", "Configure exactly one Pipeline Overview view to use this link.");
            rsp.getWriter().write(error.toString());
            return;
        }

        try {
            OverviewDataService service = new OverviewDataService();
            JSONObject result = service.fetchDashboardData(view.getGroups(), view.getHistoryDays());
            result.put("viewName", view.getDashboardTitle());
            result.put("headerMessage", view.getHeaderMessage());
            view.applyCustomStats(result);
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

    private PipelineOverviewDashboard findUniqueDashboardView() {
        List<PipelineOverviewDashboard> matches = new ArrayList<>();
        collect(Jenkins.get(), matches);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private void collect(ViewGroup group, List<PipelineOverviewDashboard> acc) {
        for (View v : group.getViews()) {
            if (v instanceof PipelineOverviewDashboard) {
                acc.add((PipelineOverviewDashboard) v);
            }
            if (v instanceof ViewGroup) {
                collect((ViewGroup) v, acc);
            }
        }
        if (group instanceof ItemGroup<?>) {
            for (Item child : ((ItemGroup<?>) group).getItems()) {
                if (child instanceof ViewGroup) {
                    collect((ViewGroup) child, acc);
                }
            }
        }
    }
}
