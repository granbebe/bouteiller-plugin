package io.jenkins.plugins.bouteiller.push;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Cause;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.scm.ChangeLogSet;
import io.jenkins.plugins.bouteiller.EventType;
import io.jenkins.plugins.bouteiller.Pipelines;
import io.jenkins.plugins.bouteiller.pipeline.PipelineRuns;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.scm.RunWithSCM;

/** Build started and completed: started, succeeded, failed, unstable, fixed, aborted. */
@Extension
public final class BuildEventListener extends RunListener<Run<?, ?>> {
    private static final Logger LOGGER = Logger.getLogger(BuildEventListener.class.getName());

    @Override
    public void onStarted(Run<?, ?> run, TaskListener listener) {
        publish(run, EventType.STARTED);
    }

    @Override
    public void onCompleted(Run<?, ?> run, @NonNull TaskListener listener) {
        EventType type = completionType(run.getResult(), previousResult(run));
        if (type != null) {
            publish(run, type);
        }
    }

    /**
     * The event of a build; the failed stage of a failed Pipeline build is looked for here, so call it off the thread
     * of the build.
     */
    public static PushEvent event(
            Run<?, ?> run,
            EventType type,
            @CheckForNull String result,
            @CheckForNull String inputId,
            @CheckForNull String inputMessage,
            @CheckForNull Boolean inputHasParameters,
            long occurredAt) {
        Job<?, ?> job = run.getParent();
        String failedStage = type == EventType.FAILED && Pipelines.isInstalled() ? PipelineRuns.failedStage(run) : null;
        return new PushEvent(
                UUID.randomUUID().toString(),
                job.getFullName(),
                job.getDisplayName(),
                run.getNumber(),
                type,
                result,
                failedStage,
                inputId,
                inputMessage,
                inputHasParameters,
                culprit(run),
                buildUrl(run),
                occurredAt);
    }

    /** The event of a completed build, {@code null} for none ({@code NOT_BUILT}). */
    @CheckForNull
    static EventType completionType(@CheckForNull Result result, @CheckForNull Result previous) {
        if (result == null || result == Result.NOT_BUILT) {
            return null;
        }
        if (result == Result.SUCCESS) {
            boolean wasBroken = previous == Result.FAILURE || previous == Result.UNSTABLE;
            return wasBroken ? EventType.FIXED : EventType.SUCCEEDED;
        }
        if (result == Result.UNSTABLE) {
            return EventType.UNSTABLE;
        }
        if (result == Result.ABORTED) {
            return EventType.ABORTED;
        }
        return EventType.FAILED;
    }

    /** Result of the last completed build before this one that was neither aborted nor not built. */
    @CheckForNull
    private static Result previousResult(Run<?, ?> run) {
        for (Run<?, ?> previous = run.getPreviousCompletedBuild();
                previous != null;
                previous = previous.getPreviousCompletedBuild()) {
            Result result = previous.getResult();
            if (result != null && result != Result.ABORTED && result != Result.NOT_BUILT) {
                return result;
            }
        }
        return null;
    }

    private static void publish(Run<?, ?> run, EventType type) {
        try {
            Result result = type == EventType.STARTED ? null : run.getResult();
            String resultName = result == null ? null : result.toString();
            long occurredAt = System.currentTimeMillis();
            PushDispatcher.get().publish(
                    run.getParent(), () -> event(run, type, resultName, null, null, null, occurredAt));
        } catch (RuntimeException e) {
            // A notification must never fail a build.
            LOGGER.log(Level.WARNING, "Cannot notify " + type.wireName() + " of " + run, e);
        }
    }

    /** Author of the last commit of the build, or else the user who started it. */
    @CheckForNull
    static String culprit(Run<?, ?> run) {
        if (run instanceof RunWithSCM<?, ?> withScm) {
            List<ChangeLogSet<? extends ChangeLogSet.Entry>> changeSets = withScm.getChangeSets();
            for (int set = changeSets.size() - 1; set >= 0; set--) {
                Object[] entries = changeSets.get(set).getItems();
                if (entries.length > 0 && entries[entries.length - 1] instanceof ChangeLogSet.Entry entry) {
                    return entry.getAuthor().getFullName();
                }
            }
        }
        Cause.UserIdCause cause = run.getCause(Cause.UserIdCause.class);
        return cause == null ? null : cause.getUserName();
    }

    @CheckForNull
    private static String buildUrl(Run<?, ?> run) {
        String root = Jenkins.get().getRootUrl();
        return root == null ? null : root + run.getUrl();
    }
}
