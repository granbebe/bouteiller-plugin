package io.jenkins.plugins.bouteiller.pipeline;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Run;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

/** Entry points on a build that may be a Pipeline; only called once {@code Pipelines.isInstalled()}. */
public final class PipelineRuns {
    private PipelineRuns() {}

    @CheckForNull
    private static FlowExecution execution(Run<?, ?> run) {
        return run instanceof WorkflowRun workflow ? workflow.getExecution() : null;
    }

    public static boolean isPipeline(Run<?, ?> run) {
        return run instanceof WorkflowRun;
    }

    /** Innermost stage where a failed Pipeline build failed, {@code null} otherwise. */
    @CheckForNull
    public static String failedStage(Run<?, ?> run) {
        FlowExecution execution = execution(run);
        return execution == null ? null : StageGraph.failedStage(execution);
    }

    /** The stages of a Pipeline build, {@code null} for another build or before its program started. */
    @CheckForNull
    public static List<StageGraph.Stage> stages(Run<?, ?> run) {
        FlowExecution execution = execution(run);
        return execution == null ? null : StageGraph.stages(execution);
    }

    /** The stages in the JSON of the API; empty before the program of the build started. */
    public static List<Map<String, Object>> stagesJson(Run<?, ?> run) {
        List<StageGraph.Stage> stages = stages(run);
        return stages == null ? List.of() : stages.stream().map(StageGraph.Stage::toJson).toList();
    }

    /** The pending inputs in the JSON of the API; empty without the input step. */
    public static List<Map<String, Object>> inputsJson(Run<?, ?> run, boolean hasInputStep)
            throws InterruptedException {
        return hasInputStep ? PendingInputs.toJson(run) : List.of();
    }
}
