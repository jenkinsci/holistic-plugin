package io.jenkins.plugins.pipelineoverview.service;

import com.cloudbees.workflow.flownode.FlowNodeUtil;
import com.cloudbees.workflow.rest.external.RunExt;
import com.cloudbees.workflow.rest.external.StageNodeExt;
import com.cloudbees.workflow.rest.external.StatusExt;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.slaves.Cloud;
import io.jenkins.plugins.pipelineoverview.DashboardEntry;
import io.jenkins.plugins.pipelineoverview.DashboardGroup;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class OverviewDataService {

    private static final Logger LOGGER =
            Logger.getLogger(OverviewDataService.class.getName());

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final long WEEK_MS = 7 * DAY_MS;
    private static final int MAX_BUILDS_PER_PIPELINE = 200;
    private static final int EXEC_TIMES_LEN = 30;
    private static final int HISTORY_DOTS_LEN = 15;
    private static final int QUEUE_HISTORY_LEN = 60;
    private static final long REGRESSION_WINDOW_MS = 24 * 60 * 60 * 1000L;
    private static final long REGRESSION_MIN_GREEN_MS = 6 * 60 * 60 * 1000L;
    private static final long LOCK_WARN_MS = 15 * 60 * 1000L;

    private static final Map<String, CachedBuilds> BUILD_CACHE = new ConcurrentHashMap<>();
    private static final long BUILD_CACHE_TTL_MS = 15_000;
    private static final Map<String, JSONArray> STAGE_TOPO_CACHE = new ConcurrentHashMap<>();
    private static final Deque<Integer> QUEUE_HISTORY = new ArrayDeque<>(QUEUE_HISTORY_LEN);

    public JSONObject fetchDashboardData(List<DashboardGroup> groups, int historyDays) {
        long now = System.currentTimeMillis();
        Jenkins jenkins = Jenkins.get();

        Map<String, PipelineSnapshot> snaps = new LinkedHashMap<>();
        Map<String, String> jobToGroup = new LinkedHashMap<>();
        for (DashboardGroup group : groups) {
            for (DashboardEntry entry : group.getPipelines()) {
                if (!entry.isEnabled()) continue;
                jobToGroup.put(entry.getJobName(), group.getName());
                try {
                    PipelineSnapshot snap = collectPipelineSnapshot(jenkins, entry, group.getName(), now, historyDays);
                    if (snap != null) snaps.put(entry.getJobName(), snap);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to collect snapshot for " + entry.getJobName(), e);
                }
            }
        }

        long sevenDaysAgo = now - WEEK_MS;
        long fourteenDaysAgo = now - 2 * WEEK_MS;
        int totalThisWeek = 0, successThisWeek = 0;
        int totalLastWeek = 0, successLastWeek = 0;

        List<PipelineSnapshot> broken = new ArrayList<>();
        List<PipelineSnapshot> unstableList = new ArrayList<>();
        int buildingCount = 0;
        int downCount = 0;
        int unstableCount = 0;

        for (PipelineSnapshot p : snaps.values()) {
            for (BuildRecord r : p.records) {
                if (r.building) continue;
                if (r.startTimeMs >= sevenDaysAgo) {
                    totalThisWeek++;
                    if ("SUCCESS".equals(r.result)) successThisWeek++;
                } else if (r.startTimeMs >= fourteenDaysAgo) {
                    totalLastWeek++;
                    if ("SUCCESS".equals(r.result)) successLastWeek++;
                }
            }
            if ("FAILURE".equals(p.lastResult)) {
                broken.add(p);
                downCount++;
            } else if ("UNSTABLE".equals(p.lastResult)) {
                broken.add(p);
                unstableList.add(p);
                unstableCount++;
            }
            if (p.building) buildingCount++;
        }

        double weekRate = totalThisWeek > 0 ? (successThisWeek * 100.0 / totalThisWeek) : 100.0;
        double weekRateLast = totalLastWeek > 0 ? (successLastWeek * 100.0 / totalLastWeek) : 100.0;

        List<JSONObject> regressions = new ArrayList<>();
        for (PipelineSnapshot p : broken) {
            if (p.brokeAtMs == 0 || p.lastGreenTimeMs == 0) continue;
            long greenDuration = p.brokeAtMs - p.lastGreenTimeMs;
            long brokeAge = now - p.brokeAtMs;
            if (greenDuration >= REGRESSION_MIN_GREEN_MS && brokeAge <= REGRESSION_WINDOW_MS) {
                JSONObject r = new JSONObject();
                r.put("jobName", p.jobName);
                r.put("displayName", p.displayName);
                r.put("greenDurationMs", greenDuration);
                r.put("brokeAtMs", p.brokeAtMs);
                r.put("failedStage", p.failedStageName != null ? p.failedStageName : "");
                r.put("buildNumber", p.lastBuildNumber);
                r.put("buildUrl", p.lastBuildUrl);
                regressions.add(r);
            }
        }
        regressions.sort((a, b) -> Long.compare(b.getLong("brokeAtMs"), a.getLong("brokeAtMs")));

        Map<String, List<String>> stageToNames = new LinkedHashMap<>();
        for (PipelineSnapshot p : broken) {
            String stage = p.failedStageName;
            if (stage == null || stage.isEmpty()) continue;
            stageToNames.computeIfAbsent(stage, k -> new ArrayList<>()).add(p.displayName);
        }
        List<JSONObject> outbreaksList = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : stageToNames.entrySet()) {
            if (e.getValue().size() < 2) continue;
            JSONObject c = new JSONObject();
            c.put("stageName", e.getKey());
            c.put("count", e.getValue().size());
            JSONArray pl = new JSONArray();
            pl.addAll(e.getValue());
            c.put("pipelines", pl);
            outbreaksList.add(c);
        }
        outbreaksList.sort((a, b) -> b.getInt("count") - a.getInt("count"));

        Queue.Item[] queueItems = Queue.getInstance().getItems();
        long avgWaitMs = 0;
        if (queueItems.length > 0) {
            long sum = 0;
            for (Queue.Item qi : queueItems) sum += now - qi.getInQueueSince();
            avgWaitMs = sum / queueItems.length;
        }
        sampleQueueHistory(queueItems.length);

        // 6. Agents
        JSONObject agentsJson = listAgents(jenkins);
        int agentsHealthy = countHealthyAgents(agentsJson);
        int agentsTotal = countTotalAgents(agentsJson);

        // 7. Build response
        JSONObject result = new JSONObject();
        result.put("timestamp", now);

        JSONObject summary = new JSONObject();
        summary.put("weekSuccessRate", round1(weekRate));
        summary.put("weekSuccessRateLastWeek", round1(weekRateLast));
        summary.put("downCount", downCount);
        summary.put("unstableCount", unstableCount);
        summary.put("buildingCount", buildingCount);
        summary.put("outbreakCount", outbreaksList.size());
        summary.put("queueSize", queueItems.length);
        summary.put("avgQueueWaitMs", avgWaitMs);
        summary.put("agentsHealthy", agentsHealthy);
        summary.put("agentsTotal", agentsTotal);
        result.put("summary", summary);

        JSONArray outbreaksArr = new JSONArray();
        outbreaksArr.addAll(outbreaksList);
        result.put("outbreaks", outbreaksArr);

        JSONArray regArr = new JSONArray();
        regArr.addAll(regressions);
        result.put("regressions", regArr);

        List<PipelineSnapshot> brokenSorted = new ArrayList<>(broken);
        brokenSorted.sort(Comparator.comparingLong((PipelineSnapshot p) -> {
            long sinceGreen = (p.brokeAtMs > 0) ? (now - p.lastGreenTimeMs) : 0;
            return -sinceGreen;
        }));
        JSONArray brokenArr = new JSONArray();
        for (PipelineSnapshot p : brokenSorted) brokenArr.add(brokenToJSON(p, now));
        result.put("broken", brokenArr);

        int hiddenInactive = 0;
        JSONArray groupsArr = new JSONArray();
        for (DashboardGroup group : groups) {
            JSONArray ps = new JSONArray();
            for (DashboardEntry entry : group.getPipelines()) {
                if (!entry.isEnabled()) continue;
                PipelineSnapshot p = snaps.get(entry.getJobName());
                if (p == null) continue;
                if (p.records.isEmpty()) { hiddenInactive++; continue; }
                ps.add(pipelineToJSON(p));
            }
            if (ps.isEmpty()) continue;
            JSONObject g = new JSONObject();
            g.put("name", group.getName());
            g.put("pipelines", ps);
            groupsArr.add(g);
        }
        result.put("groups", groupsArr);
        result.put("hiddenInactive", hiddenInactive);

        JSONArray qh = new JSONArray();
        synchronized (QUEUE_HISTORY) {
            for (Integer v : QUEUE_HISTORY) qh.add(v);
        }
        result.put("queueHistory", qh);

        result.put("locks", listLocks(now));

        unstableList.sort((a, b) -> b.unstableCount7d - a.unstableCount7d);
        JSONArray unArr = new JSONArray();
        for (PipelineSnapshot p : unstableList) {
            if (p.totalBuilds7d == 0) continue;
            JSONObject u = new JSONObject();
            u.put("jobName", p.jobName);
            u.put("displayName", p.displayName);
            u.put("unstableCount", p.unstableCount7d);
            u.put("totalCount", p.totalBuilds7d);
            unArr.add(u);
        }
        result.put("unstable", unArr);

        result.put("agents", agentsJson);

        evictStaleCache();
        return result;
    }

    private PipelineSnapshot collectPipelineSnapshot(
            Jenkins jenkins, DashboardEntry entry, String groupName,
            long now, int historyDays) {

        Item item = jenkins.getItemByFullName(entry.getJobName());
        if (!(item instanceof WorkflowJob)) return null;
        if (!item.hasPermission(Item.READ)) return null;

        WorkflowJob job = (WorkflowJob) item;
        List<BuildRecord> records = fetchBuildRecords(job, now, historyDays);

        PipelineSnapshot snap = new PipelineSnapshot();
        snap.jobName = entry.getJobName();
        snap.displayName = entry.getEffectiveDisplayName();
        snap.groupName = groupName;
        snap.records = records;

        if (records.isEmpty()) return snap;

        BuildRecord latest = records.get(0);
        snap.lastBuildNumber = latest.number;
        snap.lastBuildUrl = buildAbsoluteUrl(job, latest.number);
        snap.lastBuildTimeMs = latest.startTimeMs;
        snap.building = latest.building;
        snap.lastResult = latest.building ? "BUILDING" : (latest.result != null ? latest.result : "UNKNOWN");

        snap.stagesTopology = getStageTopology(job, latest.number);

        // junit/jacoco set build UNSTABLE/FAILURE without marking any stage; propagate
        // to the last non-skipped stage so the visual matches the build-result dot.
        if ("UNSTABLE".equals(snap.lastResult) || "FAILURE".equals(snap.lastResult)) {
            String target = "FAILURE".equals(snap.lastResult) ? "fail" : "unstable";
            propagateBuildStatusToLastStage(snap.stagesTopology, target);
        }

        long sevenAgo = now - WEEK_MS;
        for (BuildRecord r : records) {
            if (r.building) continue;
            if (r.startTimeMs >= sevenAgo) {
                snap.totalBuilds7d++;
                if ("UNSTABLE".equals(r.result)) snap.unstableCount7d++;
                else if ("FAILURE".equals(r.result)) snap.failureCount7d++;
            }
        }

        snap.successDurationsSec = extractSuccessDurations(records);
        snap.historyDots = extractHistoryDots(records);

        if ("FAILURE".equals(snap.lastResult) || "UNSTABLE".equals(snap.lastResult)) {
            int consecutive = 0;
            long brokeAt = latest.startTimeMs;
            int lastGreenBuild = 0;
            long lastGreenTime = 0;
            for (BuildRecord r : records) {
                if (r.building) continue;
                if ("FAILURE".equals(r.result) || "UNSTABLE".equals(r.result)) {
                    consecutive++;
                    brokeAt = r.startTimeMs;
                } else if ("SUCCESS".equals(r.result)) {
                    lastGreenBuild = r.number;
                    lastGreenTime = r.startTimeMs;
                    break;
                }
            }
            snap.consecutiveFailures = consecutive;
            snap.brokeAtMs = brokeAt;
            snap.lastGreenBuildNumber = lastGreenBuild;
            snap.lastGreenTimeMs = lastGreenTime;
            snap.failedStage = findFailedStage(snap.stagesTopology);
            if (snap.failedStage != null) {
                snap.failedStageName = snap.failedStage.optString("name", null);
            }
        }

        return snap;
    }

    private JSONArray getStageTopology(WorkflowJob job, int buildNumber) {
        if (buildNumber <= 0) return new JSONArray();
        String key = job.getFullName() + "#" + buildNumber;

        WorkflowRun run = job.getBuildByNumber(buildNumber);
        if (run == null) return new JSONArray();
        boolean stillBuilding = run.isBuilding();

        // Cache is build-number keyed; trust it only for finished builds whose cached
        // topology has no leftover "building" stages (those would be stale snapshots
        // from a prior poll that ran while the build was still in flight).
        JSONArray cached = STAGE_TOPO_CACHE.get(key);
        if (cached != null && !stillBuilding && !containsBuildingStatus(cached)) {
            return cached;
        }

        JSONArray topology = new JSONArray();
        try {
            FlowExecution exec = run.getExecution();
            if (exec == null) return topology;

            DepthFirstScanner scanner = new DepthFirstScanner();
            List<FlowNode> stageNodes = new ArrayList<>();
            Set<String> stageIds = new HashSet<>();
            for (FlowNode n : scanner.allNodes(exec)) {
                if (!(n instanceof BlockStartNode)) continue;
                if (n.getAction(LabelAction.class) == null) continue;
                // Drop "Branch: X" wrappers — declarative parallels emit them alongside the user stage.
                if (n.getAction(ThreadNameAction.class) != null) continue;
                stageNodes.add(n);
                stageIds.add(n.getId());
            }
            stageNodes.sort(Comparator.comparingLong(this::startTimeOf));

            Map<String, StatusExt> knownStatuses = new HashMap<>();
            try {
                RunExt runExt = RunExt.create(run);
                List<StageNodeExt> outer = runExt.getStages();
                if (outer != null) {
                    for (StageNodeExt s : outer) {
                        if (s.getStatus() != null) knownStatuses.put(s.getId(), s.getStatus());
                    }
                }
            } catch (Throwable t) {
                LOGGER.log(Level.FINE, "RunExt unavailable, falling back to graph-only", t);
            }

            Map<String, StatusExt> stageStatuses = new HashMap<>();
            for (FlowNode n : scanner.allNodes(exec)) {
                StatusExt s;
                try { s = FlowNodeUtil.getStatus(n); } catch (Throwable t) { continue; }
                if (s == null) continue;
                if (stageIds.contains(n.getId())) {
                    stageStatuses.merge(n.getId(), s, OverviewDataService::worstStatus);
                    continue;
                }
                if (s == StatusExt.SUCCESS) continue;
                List<? extends BlockStartNode> enc = n.getEnclosingBlocks();
                if (enc == null) continue;
                for (BlockStartNode b : enc) {
                    if (stageIds.contains(b.getId())) {
                        stageStatuses.merge(b.getId(), s, OverviewDataService::worstStatus);
                        break;
                    }
                }
            }

            // Two-pass build — net.sf.json's getJSONArray()-then-mutate doesn't reliably persist.
            Map<String, List<JSONObject>> childrenByParent = new LinkedHashMap<>();
            Map<Integer, String> parallelSlotIds = new LinkedHashMap<>();

            for (FlowNode n : stageNodes) {
                String stageName = labelOf(n);
                StatusExt nodeLevel = stageStatuses.get(n.getId());
                StatusExt fromRunExt = knownStatuses.get(n.getId());
                StatusExt agg;
                if (fromRunExt == StatusExt.IN_PROGRESS
                        || fromRunExt == StatusExt.PAUSED_PENDING_INPUT
                        || nodeLevel == StatusExt.IN_PROGRESS
                        || nodeLevel == StatusExt.PAUSED_PENDING_INPUT) {
                    // Stage is currently running — show that, regardless of any
                    // partial inner-step success.
                    agg = nodeLevel != null && nodeLevel == StatusExt.PAUSED_PENDING_INPUT
                            ? nodeLevel
                            : (fromRunExt != null ? fromRunExt : nodeLevel);
                } else if (nodeLevel != null) {
                    // Stage actually ran — its inner FlowNodes carry the truth.
                    agg = nodeLevel;
                } else if (fromRunExt != null) {
                    // RunExt assigned a terminal status to a stage that never executed
                    // (the build's overall result gets propagated down). Treat as skipped.
                    agg = StatusExt.NOT_EXECUTED;
                } else {
                    agg = StatusExt.SUCCESS;
                }
                String status = mapStageStatus(agg);
                String parallelParentId = parallelParentIdOf(n);

                if (parallelParentId != null) {
                    JSONObject branch = new JSONObject();
                    branch.put("name", stageName);
                    branch.put("status", status);

                    List<JSONObject> children = childrenByParent.get(parallelParentId);
                    if (children == null) {
                        children = new ArrayList<>();
                        childrenByParent.put(parallelParentId, children);
                        JSONObject placeholder = new JSONObject();
                        placeholder.put("type", "parallel");
                        parallelSlotIds.put(topology.size(), parallelParentId);
                        topology.add(placeholder);
                    }
                    children.add(branch);
                } else {
                    JSONObject cell = new JSONObject();
                    cell.put("type", "seq");
                    cell.put("name", stageName);
                    cell.put("status", status);
                    topology.add(cell);
                }
            }

            for (Map.Entry<Integer, String> e : parallelSlotIds.entrySet()) {
                List<JSONObject> children = childrenByParent.get(e.getValue());
                JSONArray arr = new JSONArray();
                if (children != null) arr.addAll(children);
                JSONObject placeholder = topology.getJSONObject(e.getKey());
                placeholder.put("children", arr);
            }

        } catch (Throwable t) {
            LOGGER.log(Level.WARNING,
                    "Failed to build stage topology for " + job.getFullName() + " #" + buildNumber, t);
        }

        if (!stillBuilding) {
            // Build is done. Any stage still flagged IN_PROGRESS was aborted/skipped — show it that way.
            sanitizeBuildingStages(topology);
            STAGE_TOPO_CACHE.put(key, topology);
        }
        return topology;
    }

    private static boolean containsBuildingStatus(JSONArray topology) {
        if (topology == null) return false;
        for (int i = 0; i < topology.size(); i++) {
            JSONObject node = topology.getJSONObject(i);
            if ("building".equals(node.optString("status"))) return true;
            JSONArray children = node.optJSONArray("children");
            if (children != null) {
                for (int j = 0; j < children.size(); j++) {
                    if ("building".equals(children.getJSONObject(j).optString("status"))) return true;
                }
            }
        }
        return false;
    }

    private static void sanitizeBuildingStages(JSONArray topology) {
        if (topology == null) return;
        for (int i = 0; i < topology.size(); i++) {
            JSONObject node = topology.getJSONObject(i);
            if ("building".equals(node.optString("status"))) node.put("status", "skipped");
            JSONArray children = node.optJSONArray("children");
            if (children != null) {
                for (int j = 0; j < children.size(); j++) {
                    JSONObject c = children.getJSONObject(j);
                    if ("building".equals(c.optString("status"))) c.put("status", "skipped");
                }
            }
        }
    }

    private void propagateBuildStatusToLastStage(JSONArray topology, String targetStatus) {
        if (topology == null || topology.isEmpty()) return;
        for (int i = 0; i < topology.size(); i++) {
            JSONObject n = topology.getJSONObject(i);
            if ("parallel".equals(n.optString("type"))) {
                JSONArray ch = n.optJSONArray("children");
                if (ch != null) for (int j = 0; j < ch.size(); j++) {
                    String st = ch.getJSONObject(j).optString("status");
                    if ("fail".equals(st) || "unstable".equals(st)) return;
                }
            } else {
                String st = n.optString("status");
                if ("fail".equals(st) || "unstable".equals(st)) return;
            }
        }
        for (int i = topology.size() - 1; i >= 0; i--) {
            JSONObject n = topology.getJSONObject(i);
            if ("parallel".equals(n.optString("type"))) {
                JSONArray ch = n.optJSONArray("children");
                if (ch == null) continue;
                for (int j = ch.size() - 1; j >= 0; j--) {
                    JSONObject c = ch.getJSONObject(j);
                    if (!"skipped".equals(c.optString("status"))) {
                        c.put("status", targetStatus);
                        return;
                    }
                }
            } else {
                if (!"skipped".equals(n.optString("status"))) {
                    n.put("status", targetStatus);
                    return;
                }
            }
        }
    }

    private long startTimeOf(FlowNode n) {
        TimingAction t = n.getAction(TimingAction.class);
        return t != null ? t.getStartTime() : 0L;
    }

    private String labelOf(FlowNode n) {
        LabelAction la = n.getAction(LabelAction.class);
        if (la != null && la.getDisplayName() != null) return la.getDisplayName();
        ThreadNameAction tna = n.getAction(ThreadNameAction.class);
        if (tna != null && tna.getThreadName() != null) return tna.getThreadName();
        return n.getDisplayName();
    }

    private static int statusRank(StatusExt s) {
        if (s == null) return 0;
        switch (s) {
            case FAILED: return 6;
            case ABORTED: return 5;
            case UNSTABLE: return 4;
            case IN_PROGRESS: return 3;
            case PAUSED_PENDING_INPUT: return 3;
            case NOT_EXECUTED: return 2;
            case SUCCESS: return 1;
            default: return 0;
        }
    }

    /** Returns the more severe of two stage statuses (used to aggregate per-stage worst). */
    private static StatusExt worstStatus(StatusExt a, StatusExt b) {
        if (a == null) return b;
        if (b == null) return a;
        return statusRank(a) >= statusRank(b) ? a : b;
    }

    /**
     * Returns the id of the parallel block enclosing this stage node, or null if the
     * stage is not inside a parallel branch.  The branch wrapper carries a
     * ThreadNameAction; the parallel block start is the next enclosing BlockStartNode.
     */
    private String parallelParentIdOf(FlowNode n) {
        if (n.getAction(ThreadNameAction.class) != null) {
            List<? extends BlockStartNode> enc = n.getEnclosingBlocks();
            if (enc != null && !enc.isEmpty()) return enc.get(0).getId();
            return "parallel:" + n.getId();
        }
        // Declarative `parallel { stage(...) }` puts ThreadNameAction on the wrapper,
        // so the inner stage finds it one level out.
        List<? extends BlockStartNode> enc = n.getEnclosingBlocks();
        if (enc == null) return null;
        for (int idx = 0; idx < enc.size(); idx++) {
            BlockStartNode b = enc.get(idx);
            if (b.getAction(ThreadNameAction.class) != null) {
                if (idx + 1 < enc.size()) return enc.get(idx + 1).getId();
                return "parallel:" + b.getId();
            }
        }
        return null;
    }

    private String mapStageStatus(StatusExt status) {
        if (status == null) return "skipped";
        switch (status) {
            case SUCCESS: return "ok";
            case FAILED:  return "fail";
            case UNSTABLE: return "unstable";
            case IN_PROGRESS: return "building";
            case PAUSED_PENDING_INPUT: return "building";
            case ABORTED: return "skipped";
            case NOT_EXECUTED: return "skipped";
            default: return "skipped";
        }
    }

    private JSONObject findFailedStage(JSONArray topology) {
        for (int i = 0; i < topology.size(); i++) {
            JSONObject node = topology.getJSONObject(i);
            if ("parallel".equals(node.optString("type"))) {
                JSONArray children = node.optJSONArray("children");
                if (children == null || children.isEmpty()) continue;
                List<JSONObject> failed = new ArrayList<>();
                for (int j = 0; j < children.size(); j++) {
                    JSONObject c = children.getJSONObject(j);
                    if ("fail".equals(c.optString("status"))) failed.add(c);
                }
                if (!failed.isEmpty()) {
                    JSONObject result = new JSONObject();
                    JSONArray failedArr = new JSONArray();
                    failedArr.addAll(failed);
                    String repr = pickParallelLabel(failedArr);
                    result.put("name", repr);
                    result.put("isParallel", true);
                    JSONArray branches = new JSONArray();
                    for (int j = 0; j < children.size(); j++) {
                        JSONObject c = children.getJSONObject(j);
                        JSONObject b = new JSONObject();
                        b.put("name", c.getString("name"));
                        b.put("status", "fail".equals(c.optString("status")) ? "FAILED" : c.optString("status"));
                        branches.add(b);
                    }
                    result.put("branches", branches);
                    return result;
                }
            } else {
                if ("fail".equals(node.optString("status"))) {
                    JSONObject result = new JSONObject();
                    result.put("name", node.getString("name"));
                    result.put("isParallel", false);
                    return result;
                }
            }
        }
        return null;
    }

    /** Longest common "X / Y" prefix across children, or the first child's name. */
    private String pickParallelLabel(JSONArray children) {
        if (children == null || children.isEmpty()) return "";
        String first = children.getJSONObject(0).getString("name");
        String prefix = first.contains(" / ") ? first.substring(0, first.indexOf(" / ")) : null;
        if (prefix != null) {
            for (int i = 1; i < children.size(); i++) {
                String n = children.getJSONObject(i).getString("name");
                if (!n.startsWith(prefix + " / ")) { prefix = null; break; }
            }
            if (prefix != null) return prefix;
        }
        return first;
    }

    private List<BuildRecord> fetchBuildRecords(WorkflowJob job, long now, int historyDays) {
        String cacheKey = job.getFullName();
        CachedBuilds cached = BUILD_CACHE.get(cacheKey);
        if (cached != null && !cached.isExpired()) return cached.records;

        List<BuildRecord> records = new ArrayList<>();
        long cutoff = now - (historyDays * DAY_MS);
        for (WorkflowRun run : job.getBuilds()) {
            if (!run.isBuilding() && run.getStartTimeInMillis() < cutoff) break;
            records.add(new BuildRecord(
                    run.getNumber(),
                    run.isBuilding() ? "BUILDING"
                            : (run.getResult() != null ? run.getResult().toString() : "UNKNOWN"),
                    run.getDuration(),
                    run.getStartTimeInMillis(),
                    run.isBuilding()));
            if (records.size() >= MAX_BUILDS_PER_PIPELINE) break;
        }
        BUILD_CACHE.put(cacheKey, new CachedBuilds(records));
        return records;
    }

    private JSONArray extractSuccessDurations(List<BuildRecord> records) {
        List<Integer> seconds = new ArrayList<>();
        for (BuildRecord r : records) {
            if (r.building) continue;
            if (!"SUCCESS".equals(r.result)) continue;
            if (r.durationMs <= 1000) continue;
            seconds.add((int) (r.durationMs / 1000));
            if (seconds.size() >= EXEC_TIMES_LEN) break;
        }
        Collections.reverse(seconds);
        JSONArray arr = new JSONArray();
        arr.addAll(seconds);
        return arr;
    }

    private JSONArray extractHistoryDots(List<BuildRecord> records) {
        List<String> dots = new ArrayList<>();
        for (BuildRecord r : records) {
            if (dots.size() >= HISTORY_DOTS_LEN) break;
            String s;
            if (r.building) s = "building";
            else if ("SUCCESS".equals(r.result)) s = "ok";
            else if ("FAILURE".equals(r.result)) s = "fail";
            else if ("UNSTABLE".equals(r.result)) s = "unstable";
            else s = "skipped";
            dots.add(s);
        }
        Collections.reverse(dots);
        JSONArray arr = new JSONArray();
        arr.addAll(dots);
        return arr;
    }

    private JSONObject pipelineToJSON(PipelineSnapshot p) {
        JSONObject o = new JSONObject();
        o.put("jobName", p.jobName);
        o.put("displayName", p.displayName);
        o.put("buildNumber", p.lastBuildNumber);
        o.put("buildUrl", p.lastBuildUrl);
        o.put("buildResult", p.lastResult != null ? p.lastResult : "UNKNOWN");
        o.put("successRate7d", p.totalBuilds7d > 0
                ? round1((p.totalBuilds7d - p.failureCount7d - p.unstableCount7d) * 100.0 / p.totalBuilds7d)
                : 100.0);
        o.put("totalBuilds7d", p.totalBuilds7d);
        o.put("failureCount7d", p.failureCount7d);
        o.put("unstableCount7d", p.unstableCount7d);
        o.put("stages", p.stagesTopology != null ? p.stagesTopology : new JSONArray());
        o.put("execTimes", p.successDurationsSec != null ? p.successDurationsSec : new JSONArray());
        return o;
    }

    private JSONObject brokenToJSON(PipelineSnapshot p, long now) {
        JSONObject o = new JSONObject();
        o.put("jobName", p.jobName);
        o.put("displayName", p.displayName);
        o.put("groupName", p.groupName);
        o.put("severity", "FAILURE".equals(p.lastResult) ? "failure" : "unstable");
        long sinceGreen = (p.lastGreenTimeMs > 0) ? (now - p.lastGreenTimeMs) : (now - p.brokeAtMs);
        o.put("sinceGreenMs", sinceGreen);
        o.put("consecutiveFailures", p.consecutiveFailures);
        o.put("lastGreenBuildNumber", p.lastGreenBuildNumber);
        o.put("buildNumber", p.lastBuildNumber);
        o.put("buildUrl", p.lastBuildUrl);
        if (p.failedStage != null) o.put("failedStage", p.failedStage);
        o.put("history", p.historyDots != null ? p.historyDots : new JSONArray());
        return o;
    }

    private void sampleQueueHistory(int currentDepth) {
        synchronized (QUEUE_HISTORY) {
            QUEUE_HISTORY.addLast(currentDepth);
            while (QUEUE_HISTORY.size() > QUEUE_HISTORY_LEN) QUEUE_HISTORY.pollFirst();
        }
    }


    private JSONObject listAgents(Jenkins jenkins) {
        JSONObject result = new JSONObject();
        JSONArray permanent = new JSONArray();
        JSONArray clouds = new JSONArray();

        for (Computer c : jenkins.getComputers()) {
            String name = c.getName();
            if (name == null || name.isEmpty()) continue;
            try {
                if (c instanceof hudson.slaves.AbstractCloudComputer) continue;
            } catch (NoClassDefFoundError ignored) {}

            JSONObject ag = new JSONObject();
            ag.put("name", name);
            String status;
            if (c.isOffline()) status = "down";
            else if (c.countBusy() >= c.getNumExecutors()) status = "partial";
            else status = "ok";
            ag.put("status", status);
            ag.put("executorsBusy", c.countBusy());
            ag.put("executorsTotal", c.getNumExecutors());
            permanent.add(ag);
        }

        for (Cloud cloud : jenkins.clouds) {
            JSONObject cl = new JSONObject();
            cl.put("name", cloud.name);
            int hot = 0;
            int max = 0;
            try {
                java.lang.reflect.Method m = cloud.getClass().getMethod("getMaxSize");
                Object v = m.invoke(cloud);
                if (v instanceof Number) max = ((Number) v).intValue();
            } catch (Exception ignored) {}
            for (Computer c : jenkins.getComputers()) {
                if (c.getName() == null || c.getName().isEmpty()) continue;
                try {
                    if (!(c instanceof hudson.slaves.AbstractCloudComputer)) continue;
                } catch (NoClassDefFoundError e) { continue; }
                if (!c.isOnline()) continue;
                if (c.getName().contains(cloud.name) || c.getName().startsWith(cloud.name)) hot++;
            }
            cl.put("hot", hot);
            cl.put("max", max);
            clouds.add(cl);
        }

        result.put("permanent", permanent);
        result.put("clouds", clouds);
        return result;
    }

    private int countHealthyAgents(JSONObject agents) {
        int healthy = 0;
        JSONArray perm = agents.optJSONArray("permanent");
        if (perm != null) {
            for (int i = 0; i < perm.size(); i++) {
                JSONObject a = perm.getJSONObject(i);
                if ("ok".equals(a.optString("status")) || "partial".equals(a.optString("status"))) healthy++;
            }
        }
        return healthy;
    }

    private int countTotalAgents(JSONObject agents) {
        JSONArray perm = agents.optJSONArray("permanent");
        return perm != null ? perm.size() : 0;
    }

    private JSONArray listLocks(long now) {
        if (Jenkins.get().getPlugin("lockable-resources") == null) {
            LOGGER.fine("lockable-resources plugin not installed — skipping locks panel");
            return new JSONArray();
        }
        return LockableResourcesAdapter.listLocks(now, LOCK_WARN_MS);
    }

    private static String buildAbsoluteUrl(WorkflowJob job, int buildNumber) {
        if (buildNumber <= 0) return "#";
        WorkflowRun run = job.getBuildByNumber(buildNumber);
        if (run == null) return "#";
        String rootUrl = Jenkins.get().getRootUrl();
        String relUrl = run.getUrl();
        if (rootUrl != null && !rootUrl.isEmpty()) {
            if (rootUrl.endsWith("/")) rootUrl = rootUrl.substring(0, rootUrl.length() - 1);
            return rootUrl + "/" + relUrl;
        }
        return "/" + relUrl;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private void evictStaleCache() {
        long cutoff = System.currentTimeMillis() - 300_000;
        BUILD_CACHE.entrySet().removeIf(e -> e.getValue().timestamp < cutoff);
        if (STAGE_TOPO_CACHE.size() > 500) {
            int target = STAGE_TOPO_CACHE.size() / 2;
            int removed = 0;
            for (String k : new ArrayList<>(STAGE_TOPO_CACHE.keySet())) {
                if (removed >= target) break;
                STAGE_TOPO_CACHE.remove(k);
                removed++;
            }
        }
    }

    private static class PipelineSnapshot {
        String jobName;
        String displayName;
        String groupName;
        List<BuildRecord> records = Collections.emptyList();
        int lastBuildNumber;
        String lastBuildUrl = "#";
        long lastBuildTimeMs;
        String lastResult;
        boolean building;
        JSONArray stagesTopology;
        JSONArray successDurationsSec;
        JSONArray historyDots;
        int totalBuilds7d;
        int unstableCount7d;
        int failureCount7d;
        int consecutiveFailures;
        long brokeAtMs;
        int lastGreenBuildNumber;
        long lastGreenTimeMs;
        JSONObject failedStage;
        String failedStageName;
    }

    private static class BuildRecord {
        final int number;
        final String result;
        final long durationMs;
        final long startTimeMs;
        final boolean building;
        BuildRecord(int number, String result, long durationMs, long startTimeMs, boolean building) {
            this.number = number;
            this.result = result;
            this.durationMs = durationMs;
            this.startTimeMs = startTimeMs;
            this.building = building;
        }
    }

    private static class CachedBuilds {
        final List<BuildRecord> records;
        final long timestamp;
        CachedBuilds(List<BuildRecord> records) {
            this.records = records;
            this.timestamp = System.currentTimeMillis();
        }
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > BUILD_CACHE_TTL_MS;
        }
    }
}
