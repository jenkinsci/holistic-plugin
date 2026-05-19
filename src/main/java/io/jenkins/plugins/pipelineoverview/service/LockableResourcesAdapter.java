package io.jenkins.plugins.pipelineoverview.service;

import hudson.model.Run;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Adapter for the optional lockable-resources plugin.
 *
 * Kept in its own class so the optional types are only resolved by the
 * classloader after the caller has confirmed the plugin is installed.
 */
final class LockableResourcesAdapter {

    private static final Logger LOGGER = Logger.getLogger(LockableResourcesAdapter.class.getName());

    private LockableResourcesAdapter() {}

    static JSONArray listLocks(long now, long lockWarnMs) {
        JSONArray arr = new JSONArray();
        try {
            LockableResourcesManager mgr = LockableResourcesManager.get();
            List<LockableResource> resources = mgr.getResources();
            for (LockableResource r : resources) {
                JSONObject l = new JSONObject();
                l.put("name", r.getName());
                boolean locked = r.isLocked();
                boolean reserved = r.isReserved();
                if (locked) {
                    long holdMs = 0L;
                    Run<?, ?> build = r.getBuild();
                    if (build != null) {
                        holdMs = now - build.getStartTimeInMillis();
                    }
                    l.put("status", "held");
                    l.put("holdMs", holdMs);
                    l.put("stale", holdMs > lockWarnMs);
                } else if (reserved) {
                    l.put("status", "reserved");
                    l.put("holdMs", 0L);
                    l.put("stale", false);
                } else {
                    l.put("status", "free");
                    l.put("holdMs", 0L);
                    l.put("stale", false);
                }
                arr.add(l);
            }
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Failed to read lockable resources", t);
        }
        return arr;
    }
}
