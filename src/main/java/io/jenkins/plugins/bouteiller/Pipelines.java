package io.jenkins.plugins.bouteiller;

import hudson.PluginWrapper;
import jenkins.model.Jenkins;

/**
 * Whether the optional Pipeline plugins are installed. Only this class may be used before checking: the
 * classes of the {@code pipeline} package refer to the Pipeline plugins and fail to load without them.
 */
public final class Pipelines {
    private Pipelines() {}

    /** Pipeline jobs and their flow graph ({@code workflow-job}, which brings {@code workflow-api}). */
    public static boolean isInstalled() {
        return isActive("workflow-job");
    }

    /** The {@code input} step. */
    public static boolean hasInputStep() {
        return isInstalled() && isActive("pipeline-input-step");
    }

    private static boolean isActive(String shortName) {
        PluginWrapper plugin = Jenkins.get().getPluginManager().getPlugin(shortName);
        return plugin != null && plugin.isActive();
    }
}
